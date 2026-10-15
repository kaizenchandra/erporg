"""Start a separate local API against an already-verified synthetic restore."""
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from restore_check import manifest, sql

ORIGIN = "http://127.0.0.1:8081"


def client():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def request(opener, path, data=None, headers=None, origin=ORIGIN):
    req = urllib.request.Request(origin + path, data=data, headers=headers or {})
    try:
        with opener.open(req, timeout=5) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        return error.code, None


def expect(condition, label):
    if not condition:
        raise RuntimeError(label)


def run():
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "status": "FAILED",
              "scope": "local synthetic restored API; excludes production infrastructure and IdP recovery"}
    process = None
    started = time.monotonic()
    try:
        restore = json.loads(Path("docs/evidence/restore-check.json").read_text())
        target = restore["targetDatabase"]
        expect(restore["status"] == "PASSED" and re.fullmatch(r"atlas_restore_[a-f0-9]{16}", target), "Verified synthetic restore target required")
        expect(restore["sourceSchema"] == "atlas_acceptance", "Unexpected source schema")
        report["targetDatabase"] = target
        private_root = restore["privateObjects"]["restoredRoot"]
        expect(restore["privateObjects"]["status"] == "PASSED" and re.fullmatch(r"/private/tmp/atlas-document-restore-[a-f0-9]{16}", private_root), "Verified independent private-object copy required")
        before = manifest(target)
        prior_document_audit_max = int(sql(target, "select coalesce(max(id),0) from atlas_acceptance.employee_document_audit"))
        prior_document_audit_digest = hashlib.sha256(sql(target, "select row_to_json(a)::text from atlas_acceptance.employee_document_audit a order by id").encode()).hexdigest()
        expect(before == restore["tableManifest"], "Restore changed since its verification")
        with socket.socket() as sock:
            expect(sock.connect_ex(("127.0.0.1", 8081)) != 0, "Port 8081 is already occupied; no process will be stopped")
        with tempfile.TemporaryFile() as log:
            process = subprocess.Popen([
                "mvn", "-f", "backend/pom.xml", "-Dmaven.repo.local=/private/tmp/atlas-m2", "spring-boot:run", "-Dspring-boot.run.profiles=local",
                "-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8081 "
                f"--spring.datasource.url=jdbc:postgresql://localhost:5438/{target}?currentSchema=atlas_acceptance "
                f"--spring.flyway.default-schema=atlas_acceptance --spring.flyway.schemas=atlas_acceptance --atlas.documents.local-root={private_root}", "-q"
            ], stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            anonymous = client()
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                expect(process.poll() is None, "Restored API exited during startup")
                try:
                    status, health = request(anonymous, "/actuator/health/readiness")
                    if status == 200 and health["status"] == "UP":
                        break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(0.5)
            else:
                raise RuntimeError("Restored API did not become ready within 60 seconds")
            report["readinessSeconds"] = round(time.monotonic() - started, 3)
            log.seek(0)
            startup = log.read().decode(errors="replace")
            expect("Successfully validated" in startup, "Flyway validation evidence missing")
            report["flywayStartupValidation"] = True
            expect(request(anonymous, "/api/v1/people-summary")[0] == 401, "Anonymous request was not denied")
            for user, role, employee_id in [("amelia", "HR", "E001"), ("sophia", "EMPLOYEE", "E003"), ("outsider", "EMPLOYEE", "E900")]:
                session = client()
                status, csrf = request(session, "/api/csrf")
                expect(status == 200, "CSRF establishment failed")
                login = urllib.parse.urlencode({"username": user, "password": "atlas-demo"}).encode()
                expect(request(session, "/api/login", login, {csrf["headerName"]: csrf["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 204, "Synthetic sign-in failed")
                status, actor = request(session, "/api/v1/session")
                expect(status == 200 and actor["role"] == role and actor["employeeId"] == employee_id, "Restored identity mapping differs")
                status, summary = request(session, "/api/v1/people-summary")
                expect(status == 200 and summary["total"] == (int(sql(target, "select count(*) from atlas_acceptance.hr_employee where tenant='atlas'")) if role == "HR" else 1), "Restored scope count differs")
                status, employee = request(session, f"/api/v1/employees/{employee_id}")
                expect(status == 200 and employee["id"] == employee_id, "Restored profile unavailable")
                if role == "HR":
                    status, departments = request(session, "/api/v1/organisation/departments")
                    expect(status == 200 and len(departments) == int(sql(target, "select count(*) from atlas_acceptance.hr_department where tenant='atlas'")), "Restored department references differ")
                    status, department_audit = request(session, "/api/v1/organisation/history")
                    expect(status == 200 and len(department_audit) > 0, "Restored department audit unavailable")
                    status, assigned_profile = request(session, "/api/v1/employees/E003")
                    expect(status == 200 and isinstance(assigned_profile["positionCode"], str), "Restored employee position unavailable")
                    status, assignment_history = request(session, "/api/v1/employees/E003/history")
                    expect(status == 200 and any(h["field"] == "positionCode" and h["action"] == "APPROVED" for h in assignment_history), "Restored position approval history missing")
                    expect(any(h["field"] == "name" and h["action"] == "APPROVED" for h in assignment_history), "Restored display-name approval history missing")
                    status, withdrawals = request(session, "/api/v1/request-inbox?status=WITHDRAWN&size=1")
                    expect(status == 200 and withdrawals["total"] > 0 and withdrawals["items"][0]["status"] == "WITHDRAWN", "Restored withdrawn requests unavailable")
                    status, benefits = request(session, "/api/v1/benefits/plans?size=1")
                    expect(status == 200 and benefits["total"] == int(sql(target, "select count(*) from atlas_acceptance.benefit_plan where tenant='atlas'")) and benefits["items"][0]["mode"] == "DEMONSTRATION", "Restored benefit catalogue differs")
                    status, selections = request(session, "/api/v1/benefits/selections?size=100")
                    expect(status == 200 and selections["total"] == int(sql(target, "select count(*) from atlas_acceptance.benefit_selection where tenant='atlas'")), "Restored benefit selections differ")
                    expect(any(s["status"] == "APPROVED" and s["handoffStatus"] == "PENDING_PAYROLL" and len(s["history"]) == 2 for s in selections["items"]), "Restored approved benefit handoff unavailable")
                    retry_document = sql(target, "select document_id from atlas_acceptance.employee_document_scan_retry where tenant='atlas' and status='AVAILABLE' order by recorded_at desc limit 1")
                    expect(bool(re.fullmatch(r"[a-f0-9-]{36}",retry_document)), "Restored successful rescan fixture required")
                    status, retry_history = request(session, "/api/v1/documents/" + retry_document + "/history")
                    expect(status == 200 and any(e["action"] == "RESCAN_AVAILABLE" for e in retry_history), "Restored rescan audit unavailable")
                    governance_root = sql(target, "select root_id from atlas_acceptance.document_retention_event where tenant='atlas' and action='RELEASE_HOLD' order by recorded_at desc limit 1")
                    expect(bool(re.fullmatch(r"[a-f0-9-]{36}", governance_root)), "Restored hold/release fixture required")
                    status, governance = request(session, "/api/v1/documents/" + governance_root + "/retention")
                    expect(status == 200 and not governance["deletionAvailable"] and len(governance["history"]) >= 3 and any(h["action"] == "RELEASE_HOLD" for h in governance["history"]) and any(not h["active"] for h in governance["holds"]), "Restored governance history/state differs")
                    expect(governance["version"] == int(sql(target, "select max(version) from atlas_acceptance.document_retention_event where tenant='atlas' and root_id='" + governance_root + "'")), "Restored governance ordinal differs")
                    status, documents = request(session, "/api/v1/documents?size=100")
                    expect(status == 200 and documents["total"] == int(sql(target, "select count(*) from atlas_acceptance.employee_document d left join atlas_acceptance.employee_document_version v on v.tenant=d.tenant and v.document_id=d.id where d.tenant='atlas' and not exists(select 1 from atlas_acceptance.employee_document_version n where n.tenant=d.tenant and n.root_id=coalesce(v.root_id,d.id) and n.version>coalesce(v.version,1))")), "Restored document catalogue differs")
                    status, configured_templates = request(session, "/api/v1/document-templates")
                    expect(status == 200 and len(configured_templates) == int(sql(target, "select count(*) from atlas_acceptance.document_template where tenant='atlas'")) and len(configured_templates) > 0, "Restored templates differ")
                    status, classification = request(session, "/api/v1/document-templates/evidence?employeeId=E003")
                    expect(status == 200 and len(classification) == int(sql(target, "select least(100,(select count(*) from atlas_acceptance.document_template_evidence where tenant='atlas' and employee_id='E003')+(select count(*) from atlas_acceptance.document_template_reclassification where tenant='atlas' and employee_id='E003'))")) and len(classification) > 0, "Restored classification history differs")
                    retired_code = sql(target, "select code from atlas_acceptance.document_template_revision where tenant='atlas' and retired order by recorded_at desc limit 1")
                    expect(bool(re.fullmatch(r"[A-Z][A-Z0-9_-]{1,31}", retired_code)), "Retired template fixture required")
                    status, revisions = request(session, "/api/v1/document-templates/" + retired_code + "/revisions")
                    expect(status == 200 and len(revisions) >= 3 and revisions[0]["retired"] and revisions[-1]["version"] == 1, "Restored revision/retirement history differs")
                    correction_code = sql(target, "select code from atlas_acceptance.document_template_reclassification where tenant='atlas' and employee_id='E003' order by recorded_at desc limit 1")
                    expect(bool(re.fullmatch(r"[A-Z][A-Z0-9_-]{1,31}", correction_code)), "Corrected classification fixture required")
                    status, corrections = request(session, "/api/v1/document-templates/" + correction_code + "/evidence-history?employeeId=E003")
                    expect(status == 200 and len(corrections) >= 2 and corrections[0]["classificationVersion"] > 1 and corrections[0]["previousRootId"] == corrections[1]["rootId"], "Restored correction history differs")
                    status, completeness = request(session, "/api/v1/document-templates/completeness?employeeId=E003")
                    expect(status == 200 and len(completeness["items"]) == len(configured_templates) and any(i["state"] == "PRESENT" for i in completeness["items"]) and any(i["state"] == "BLOCKED" for i in completeness["items"]), "Restored latest-version completeness differs")
                    version_root = sql(target, "select root_id from atlas_acceptance.employee_document_version where tenant='atlas' order by recorded_at desc limit 1")
                    expect(bool(re.fullmatch(r"[a-f0-9-]{36}",version_root)), "Restored document version fixture required")
                    status, chain = request(session, "/api/v1/documents/" + version_root + "/versions")
                    expect(status == 200 and len(chain) >= 2 and chain[0]["version"] > 1 and chain[-1]["id"] == version_root and all(v["rootId"] == version_root for v in chain), "Restored document versions differ")
                    clean = next((d for d in documents["items"] if d["id"] == retry_document and d["status"] == "AVAILABLE" and d["scanAttempts"] > 0), None)
                    expect(clean is not None, "Restored clean document fixture required")
                    expect(re.fullmatch(r"[a-f0-9-]{36}", clean["id"]) is not None, "Unexpected document identifier")
                    with session.open(ORIGIN + "/api/v1/documents/" + clean["id"] + "/content", timeout=5) as response:
                        content = response.read(16385)
                        expect(response.status == 200 and len(content) == clean["bytes"], "Restored private download failed")
                        expected_hash = sql(target, "select sha256 from atlas_acceptance.employee_document where id='" + clean["id"] + "'")
                        expect(hashlib.sha256(content).hexdigest() == expected_hash, "Restored private content differs")
                    status, benefit_audit = request(session, "/api/v1/benefits/history")
                    expect(status == 200 and len(benefit_audit) > 0, "Restored benefit audit unavailable")
                    status, reporting_candidates = request(session, "/api/v1/reporting/managers?size=1")
                    expect(status == 200 and reporting_candidates["total"] > 0 and len(reporting_candidates["items"]) == 1, "Restored reporting manager candidates unavailable")
                    expect(any(h["field"] == "managerId" and h["action"] == "APPROVED" for h in assignment_history), "Restored reporting approval history missing")
                    status, positions = request(session, "/api/v1/organisation/positions?size=1")
                    expect(status == 200 and positions["total"] == int(sql(target, "select count(*) from atlas_acceptance.hr_position where tenant='atlas'")) and len(positions["items"]) == 1, "Restored position catalogue differs")
                    status, position_audit = request(session, "/api/v1/organisation/positions/history")
                    expect(status == 200 and len(position_audit) > 0, "Restored position audit unavailable")
                    status, hires = request(session, "/api/v1/hiring/requests?status=APPROVED")
                    expect(status == 200 and hires["total"] == int(sql(target, "select count(*) from atlas_acceptance.hr_hire_request where tenant='atlas' and status='APPROVED'")), "Restored approved hires differ")
                    completion_employee = sql(target, "select employee_id from atlas_acceptance.hr_onboarding_completion where tenant='atlas' order by recorded_at limit 1")
                    expect(bool(completion_employee), "Restore smoke needs a completed onboarding fixture")
                    expect(bool(re.fullmatch(r"E-[a-f0-9-]{36}", completion_employee)), "Unexpected restored employee identifier")
                    status, completed_profile = request(session, "/api/v1/employees/" + urllib.parse.quote(completion_employee))
                    expected_status = sql(target, "select status from atlas_acceptance.hr_employee where tenant='atlas' and id='" + completion_employee + "'")
                    expect(status == 200 and completed_profile["status"] == expected_status, "Restored employment status differs")
                    status, completion_history = request(session, "/api/v1/hiring/requests/employees/" + urllib.parse.quote(completion_employee) + "/employment-history")
                    expect(status == 200 and len(completion_history) == 1, "Restored onboarding history missing")
                    status, inbox = request(session, "/api/v1/request-inbox?status=APPROVED")
                    expect(status == 200 and inbox["total"] == restore["approvedRequests"], "Restored approvals differ")
                    status, history = request(session, "/api/v1/employees/E003/history")
                    expect(status == 200 and any(item["action"] == "APPROVED" for item in history), "Restored approval audit unavailable")
                else:
                    expect(request(session, "/api/v1/documents/" + governance_root + "/retention")[0] == 403, "Private governance denial failed")
                    expect(request(session, "/api/v1/employees/E001")[0] == 403, "Record/tenant denial failed")
                    if employee_id != "E003":
                        expect(request(session, "/api/v1/document-templates/completeness?employeeId=E003")[0] == 403, "Restored completeness isolation failed")
                        expect(request(session, "/api/v1/document-templates/evidence?employeeId=E003")[0] == 403, "Restored evidence isolation failed")
                    if employee_id != clean["employeeId"]:
                        expect(request(session, "/api/v1/documents/" + clean["id"] + "/content")[0] == 403, "Restored document record/tenant denial failed")
                _, fresh = request(session, "/api/csrf")
                expect(request(session, "/api/logout", b"", {fresh["headerName"]: fresh["token"]})[0] == 204, "Logout failed")
                expect(request(session, "/api/v1/session")[0] == 401, "Logged-out session retained access")
            after = manifest(target)
            expect({k:v for k,v in after.items() if k != "employee_document_audit"} == {k:v for k,v in before.items() if k != "employee_document_audit"}, "Application smoke changed restored business data")
            expect(after["employee_document_audit"]["rows"] == before["employee_document_audit"]["rows"] + 1, "Exactly one restored download audit required")
            retained_audit = sql(target, "select row_to_json(a)::text from atlas_acceptance.employee_document_audit a where id<=" + str(prior_document_audit_max) + " order by id")
            expect(hashlib.sha256(retained_audit.encode()).hexdigest() == prior_document_audit_digest, "Original restored document audit changed")
            expect(sql(target, "select action from atlas_acceptance.employee_document_audit where id>" + str(prior_document_audit_max)) == "DOWNLOAD_AUTHORISED", "Unexpected restored audit mutation")
            report["checks"] = ["readiness", "Flyway validation", "anonymous denial", "three identity mappings", "scoped counts", "profile reads", "approved requests", "approval audit", "department references and audit", "position catalogue and audit", "employee position and approval history", "reporting candidates and approval history", "display-name approval history", "withdrawn request history", "benefits catalogue and audit", "benefit selections, audit and pending deduction", "independent private document storage, download integrity and new audit", "immutable rescan history and effective availability", "immutable document version chain and latest projection", "document templates, classification audit and latest completeness", "effective template revisions/retirement and correction history", "private retention/hold journal and independent release", "approved hires", "onboarding status/history", "record/tenant denial", "logout revocation", "unchanged business data"]
            report["status"] = "PASSED"
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        report["error"] = str(error) if isinstance(error, RuntimeError) else "Restore evidence or local tool unavailable"
    finally:
        if process and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        report["totalSeconds"] = round(time.monotonic() - started, 3)
        Path("docs/evidence/restore-application-check.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(run())
