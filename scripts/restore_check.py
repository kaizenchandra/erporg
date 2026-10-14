"""Local synthetic restore drill. Never targets or overwrites an existing database."""
import hashlib
import json
from pathlib import Path
import subprocess
import time
from datetime import datetime, timezone
from uuid import uuid4

BASE = ["docker", "compose", "exec", "-T", "postgres"]
SCHEMA = "atlas_acceptance"  # Synthetic browser fixtures only; never select a live schema.
TABLES = ["flyway_schema_history", "hr_employee", "identity_membership", "hr_request", "hr_version", "hr_audit", "hr_notification", "hr_department", "hr_department_audit", "hr_hire_request", "hr_hire_audit", "hr_employee_event"]


def command(args, payload=None):
    result = subprocess.run(BASE + args, input=payload, capture_output=True, timeout=120)
    if result.returncode:
        # Do not log stdout/stderr: database failures can contain personal data.
        raise RuntimeError(f"{args[0]} failed with exit {result.returncode}; inspect the local database privately")
    return result.stdout


def sql(database, statement):
    return command(["psql", "-X", "-U", "atlas", "-d", database, "-v", "ON_ERROR_STOP=1", "-Atc", statement]).decode().strip()


def manifest(database):
    result = {}
    for table in TABLES:
        # Canonical row order and complete row content; only digests leave this process.
        rows = sql(database, f'SELECT row_to_json(t)::text FROM "{SCHEMA}"."{table}" t ORDER BY row_to_json(t)::text COLLATE "C";')
        count = int(sql(database, f'SELECT count(*) FROM "{SCHEMA}"."{table}"'))
        result[table] = {"rows": count, "sha256": hashlib.sha256(rows.encode()).hexdigest()}
    return result


def run(evidence=Path("docs/evidence/restore-check.json")):
    started = time.monotonic()
    target = "atlas_restore_" + uuid4().hex[:16]
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "sourceSchema": SCHEMA,
              "targetDatabase": target, "scope": "local synthetic PostgreSQL logical restore; not production RPO/RTO", "status": "FAILED"}
    created = False
    try:
        before = manifest("atlas")
        dump = command(["pg_dump", "-U", "atlas", "-d", "atlas", "-Fc", "--schema=" + SCHEMA, "--exclude-table-data=" + SCHEMA + ".spring_session", "--exclude-table-data=" + SCHEMA + ".spring_session_attributes", "--no-owner", "--no-acl"])
        report["backupBytes"] = len(dump)
        report["backupSha256"] = hashlib.sha256(dump).hexdigest()
        # createdb fails if a target exists. No DROP, --clean or replacement operations.
        command(["createdb", "-U", "atlas", target])
        created = True
        restore_started = time.monotonic()
        command(["pg_restore", "-U", "atlas", "-d", target, "--exit-on-error", "--no-owner", "--no-acl"], dump)
        report["restoreSeconds"] = round(time.monotonic() - restore_started, 3)
        after = manifest(target)
        if before != after:
            raise RuntimeError("Restored manifest differs; source may have changed during the drill")
        report["restoredSessionRows"] = int(sql(target, f"select count(*) from {SCHEMA}.spring_session"))
        report["restoredSessionAttributeRows"] = int(sql(target, f"select count(*) from {SCHEMA}.spring_session_attributes"))
        if report["restoredSessionRows"] or report["restoredSessionAttributeRows"]:
            raise RuntimeError("Restore must not revive persisted sessions")
        report["tableManifest"] = after
        checks = {
            "orphanRequests": "select count(*) from atlas_acceptance.hr_request r left join atlas_acceptance.hr_employee e on e.tenant=r.tenant and e.id=r.employee_id where e.id is null",
            "orphanVersions": "select count(*) from atlas_acceptance.hr_version v left join atlas_acceptance.hr_request r on r.id=v.request_id and r.tenant=v.tenant where r.id is null",
            "orphanAudit": "select count(*) from atlas_acceptance.hr_audit a left join atlas_acceptance.hr_request r on r.id=a.request_id and r.tenant=a.tenant where r.id is null",
            "orphanDepartments": "select count(*) from atlas_acceptance.hr_department d left join atlas_acceptance.hr_department p on p.tenant=d.tenant and p.code=d.parent_code where d.parent_code is not null and p.code is null",
            "orphanDepartmentAudit": "select count(*) from atlas_acceptance.hr_department_audit a left join atlas_acceptance.hr_department d on d.tenant=a.tenant and d.code=a.code where d.code is null",
            "orphanHires": "select count(*) from atlas_acceptance.hr_hire_request h left join atlas_acceptance.hr_employee e on e.tenant=h.tenant and e.id=h.employee_id where h.status='APPROVED' and e.id is null",
            "orphanHireAudit": "select count(*) from atlas_acceptance.hr_hire_audit a left join atlas_acceptance.hr_hire_request h on h.id=a.request_id and h.tenant=a.tenant where h.id is null",
            "orphanEmployeeEvents": "select count(*) from atlas_acceptance.hr_employee_event v left join atlas_acceptance.hr_employee e on e.tenant=v.tenant and e.id=v.employee_id where e.id is null",
            "failedMigrations": "select count(*) from atlas_acceptance.flyway_schema_history where not success",
        }
        report["integrityChecks"] = {key: int(sql(target, statement)) for key, statement in checks.items()}
        if any(report["integrityChecks"].values()):
            raise RuntimeError("Restored integrity checks failed")
        report["auditTriggerRestored"] = int(sql(target, "select count(*) from pg_trigger where tgrelid='atlas_acceptance.hr_audit'::regclass and tgname='immutable_audit' and tgenabled='O'")) == 1
        if not report["auditTriggerRestored"]:
            raise RuntimeError("Immutable audit trigger missing")
        report["approvedRequests"] = int(sql(target, "select count(*) from atlas_acceptance.hr_request where status='APPROVED'"))
        if not report["approvedRequests"] or not after["hr_audit"]["rows"] or not after["hr_version"]["rows"]:
            raise RuntimeError("Drill needs synthetic approved workflow, version and audit data")
        # Verify the restored trigger actually rejects changes; the nested block always rolls back.
        sql(target, """DO $drill$ BEGIN
          BEGIN
            UPDATE atlas_acceptance.hr_audit SET reason='restore probe' WHERE id=(SELECT min(id) FROM atlas_acceptance.hr_audit);
            RAISE EXCEPTION 'audit guard did not reject update' USING ERRCODE='ZX001';
          EXCEPTION WHEN raise_exception THEN
            IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF;
          END;
        END $drill$;""")
        report["auditMutationRejected"] = True
        if manifest(target) != after:
            raise RuntimeError("Readiness probe changed restored content")
        report["status"] = "PASSED"
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        report["error"] = str(error) if isinstance(error, RuntimeError) else "Local tool unavailable or timed out"
    finally:
        report["totalSeconds"] = round(time.monotonic() - started, 3)
        report["targetRetained"] = created
        evidence.parent.mkdir(parents=True, exist_ok=True)
        evidence.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps({key: report[key] for key in ["status", "targetDatabase", "totalSeconds", "scope"]}))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(run())
