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
from restore_check import manifest, projection_manifest, sql

ORIGIN = "http://127.0.0.1:8081"


def client():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


READ_RATE_RETRIES = 0


def request(opener, path, data=None, headers=None, origin=ORIGIN):
    global READ_RATE_RETRIES
    # Only reads retry here. Mutations must retain their own explicit idempotency semantics.
    for attempt in range(2):
        req = urllib.request.Request(origin + path, data=data, headers=headers or {})
        try:
            with opener.open(req, timeout=5) as response:
                body = response.read()
                return response.status, json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            if error.code == 429 and data is None and attempt == 0:
                try:
                    delay = min(60, max(1, int(error.headers.get("Retry-After", "1"))))
                except (ValueError, AttributeError):
                    delay = 1
                error.close()
                READ_RATE_RETRIES += 1
                time.sleep(delay)
                continue
            return error.code, None


def expect(condition, label):
    if not condition:
        raise RuntimeError(label)


def run(restore_evidence=Path("docs/evidence/restore-check.json"), evidence=Path("docs/evidence/restore-application-check.json"), verify_legal_name=False, verify_additional_contacts=False, verify_contact_inbox=False, verify_benefit_withdrawal=False, verify_postal_address=False, verify_legal_entities=False, verify_reference_retirement=False, verify_retirement_notifications=False, verify_retirement_inbox=False, verify_birth_date=False, verify_company_calendars=False, verify_calendar_amendments=False, verify_calendar_retirement=False, verify_personal_email=False, verify_employee_calendar=False, verify_calendar_history=False, verify_entity_assignment=False, verify_audit_pagination=False, verify_department_directory=False, verify_reference_history=False, verify_benefit_history=False, verify_benefit_catalogue=False, verify_benefit_inbox=False, verify_department_parents=False, verify_timekeeping=False):
    global READ_RATE_RETRIES
    READ_RATE_RETRIES = 0
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "status": "FAILED",
              "scope": "local synthetic restored API; excludes production infrastructure and IdP recovery"}
    process = None
    started = time.monotonic()
    try:
        expect(not (verify_retirement_notifications or verify_retirement_inbox or verify_birth_date or verify_company_calendars or verify_calendar_amendments or verify_calendar_retirement or verify_personal_email or verify_employee_calendar or verify_calendar_history or verify_entity_assignment or verify_audit_pagination or verify_department_directory or verify_reference_history or verify_benefit_history or verify_benefit_catalogue or verify_benefit_inbox or verify_department_parents) or verify_contact_inbox, "Retirement verification requires the existing multi-role inbox reader checks")
        restore = json.loads(restore_evidence.read_text())
        target = restore["targetDatabase"]
        expect(restore["status"] == "PASSED" and re.fullmatch(r"atlas_restore_[a-f0-9]{16}", target), "Verified synthetic restore target required")
        expect(restore["sourceSchema"] == "atlas_acceptance", "Unexpected source schema")
        report["targetDatabase"] = target
        private_root = restore["privateObjects"]["restoredRoot"]
        expected_root = Path(__file__).resolve().parent.parent / "backend" / ".local" / "restored-documents" / target.removeprefix("atlas_restore_")
        expect(restore["privateObjects"]["status"] == "PASSED" and Path(private_root) == expected_root and expected_root.resolve() == expected_root and not expected_root.is_symlink(), "Verified independent private-object copy required")
        before = manifest(target)
        projections_before = projection_manifest(target)
        expect(projections_before == restore["projectionManifest"], "Restored task projection changed since verification")
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
                status, chart = request(session, "/api/v1/organisation/chart")
                expect(status == 200 and chart["mode"] == "ROOTS" and chart["parent"] is None and chart["manager"] is None, "Restored chart roots unavailable")
                allowed_node_fields = {"id", "name", "title", "department", "positionCode", "status", "visibleDirectReports"}
                expect(all(set(node) == allowed_node_fields for node in chart["items"]), "Chart exposes unapproved fields")
                if role != "HR":
                    expect([node["id"] for node in chart["items"]] == [employee_id] and chart["items"][0]["visibleDirectReports"] == 0, "Restored chart self scope differs")
                    expect(request(session, "/api/v1/organisation/chart?parentId=E001")[0] == 403, "Restored chart parent scope denial failed")
                status, resignations = request(session, "/api/v1/resignations?size=100")
                count_scope = "" if role == "HR" else " and employee_id='" + employee_id + "'"
                expect(status == 200 and resignations["total"] == int(sql(target, "select count(*) from atlas_acceptance.hr_resignation where tenant='" + actor["tenant"] + "'" + count_scope)), "Restored resignation scope/count differs")
                resignation_id = sql(target, "select id from atlas_acceptance.hr_resignation where tenant='atlas' and employee_id='E003' and status='APPROVED' limit 1")
                expect(bool(re.fullmatch(r"[a-f0-9-]{36}", resignation_id)), "Approved synthetic resignation fixture required")
                status, resignation_history = request(session, "/api/v1/resignations/" + resignation_id + "/history")
                if role == "HR" or employee_id == "E003":
                    expect(status == 200 and len(resignation_history) == 2 and resignation_history[-1]["newStatus"] == "APPROVED", "Restored resignation history differs")
                else:
                    expect(status == 403, "Restored resignation tenant denial failed")
                withdrawn_id = sql(target, "select request_id from atlas_acceptance.hr_resignation_withdrawal where tenant='atlas' and employee_id='E005' order by recorded_at limit 1")
                expect(bool(re.fullmatch(r"[a-f0-9-]{36}", withdrawn_id)), "Withdrawn synthetic resignation fixture required")
                status, withdrawal_receipt = request(session, "/api/v1/resignations/" + withdrawn_id + "/withdrawal")
                if role == "HR" or employee_id == "E005":
                    expect(status == 200 and withdrawal_receipt["receipt"]["requestId"] == withdrawn_id and withdrawal_receipt["receipt"]["actor"] == "olivia" and withdrawal_receipt["receipt"]["newVersion"] == 1, "Restored private withdrawal receipt differs")
                    status, withdrawn_history = request(session, "/api/v1/resignations/" + withdrawn_id + "/history")
                    expect(status == 200 and len(withdrawn_history) == 2 and withdrawn_history[-1]["newStatus"] == "WITHDRAWN" and withdrawn_history[-1]["reason"] == withdrawal_receipt["receipt"]["reason"] and withdrawn_history[-1]["correlationId"] == withdrawal_receipt["receipt"]["correlationId"], "Restored withdrawal audit differs")
                else:
                    expect(status == 403, "Restored withdrawal receipt privacy failed")
                status, case_episode = request(session, "/api/v1/resignations/" + resignation_id + "/episode")
                if role == "HR" or employee_id == "E003":
                    expect(status == 200 and case_episode["employeeId"] == "E003" and case_episode["episodeNumber"] == 1 and case_episode["origin"] == "COMMAND" and case_episode["sourceRecordedAt"] == case_episode["linkedAt"] and case_episode["linkedBy"] == "sophia", "Restored case episode ownership differs")
                    expect(int(sql(target, "select episode_number from atlas_acceptance.hr_episode_end_current where tenant='atlas' and request_id='" + resignation_id + "'")) == 1, "Restored end episode ownership differs")
                else:
                    expect(status == 403, "Restored private case episode denial failed")
                status, own_tasks = request(session, "/api/v1/exit-tasks?size=100")
                expect(status == 200 and own_tasks["total"] == int(sql(target, "select count(*) from atlas_acceptance.hr_exit_task_current where tenant='" + actor["tenant"] + "' and assignee_id='" + employee_id + "'")), "Restored own-task scope differs")
                status, case_tasks = request(session, "/api/v1/resignations/" + resignation_id + "/tasks")
                if role == "HR":
                    expect(status == 200 and case_tasks["version"] == int(sql(target, "select count(*) from atlas_acceptance.hr_exit_task_current where tenant='atlas' and request_id='" + resignation_id + "'")) and len(case_tasks["items"]) >= 2, "Restored HR case tasks differ")
                else:
                    expect(status == 403, "Restored private case task denial failed")
                managed_id = sql(target, "select task_id from atlas_acceptance.hr_exit_task_management m where tenant='atlas' and action='CANCEL' and new_assignee_id='E003' and exists(select 1 from atlas_acceptance.hr_exit_task_management p where p.tenant=m.tenant and p.task_id=m.task_id and p.action='REASSIGN') order by recorded_at desc limit 1")
                expect(bool(re.fullmatch(r"[a-f0-9-]{36}", managed_id)), "Managed cancellation fixture required")
                status, management = request(session, "/api/v1/exit-tasks/" + managed_id + "/management-history")
                if role == "HR":
                    expect(status == 200 and len(management) == 2 and management[0]["action"] == "REASSIGN" and management[1]["newStatus"] == "CANCELLED", "Restored management journal differs")
                else:
                    expect(status == 403, "Private management history denial failed")
                if employee_id == "E003":
                    expect(any(item["task"]["id"] == managed_id and item["task"]["status"] == "CANCELLED" and item["task"]["assigneeId"] == "E003" for item in own_tasks["items"]), "Restored cancellation projection absent from current assignee")
                status, end_context = request(session, "/api/v1/resignations/" + resignation_id + "/employment-end")
                if role == "HR":
                    expect(status == 200 and end_context["record"] is not None and end_context["phase"] == "SCHEDULED" and end_context["replaced"] is True and end_context["record"]["employeeId"] == "E003" and end_context["openTasks"] == 0, "Restored replacement employment end differs")
                    expect(end_context["inactiveDate"] == sql(target, "select inactive_date from atlas_acceptance.hr_employment_end_current where tenant='atlas' and employee_id='E003'"), "Restored inactive date differs")
                    expect(int(sql(target, "select count(*) from atlas_acceptance.hr_employment_end_handoff where tenant='atlas' and employee_id='E003' and status='PENDING'")) == 2, "Restored downstream handoffs missing")
                    expect([w["status"] for w in end_context["withdrawals"]] == ["REJECTED", "APPROVED"], "Restored withdrawal history differs")
                    expect(end_context["withdrawals"][-1]["reviewer"] == "olivia", "Restored independent reviewer differs")
                    expect(int(sql(target, "select count(*) from atlas_acceptance.hr_employment_end_handoff_current where tenant='atlas' and employee_id='E003' and status='SUPPRESSED'")) == 2, "Restored suppressed handoffs differ")
                    expect(int(sql(target, "select count(*) from atlas_acceptance.hr_employment_end_handoff_current where tenant='atlas' and employee_id='E003' and status='PENDING'")) == 2, "Restored replacement handoffs differ")
                    status, replacement = request(session, "/api/v1/resignations/" + resignation_id + "/employment-end/rescheduling")
                    expect(status == 200 and [r["status"] for r in replacement["history"]] == ["REJECTED", "APPROVED"] and replacement["currentSchedule"]["inactiveDate"] == end_context["inactiveDate"], "Restored replacement date/history differs")
                    expect(replacement["history"][-1]["priorInactiveDate"] is None and replacement["history"][-1]["reviewer"] == "olivia", "Restored withdrawn-to-replacement review differs")
                    expect(sql(target, "set search_path=atlas_acceptance; select hr_employment_status('atlas'::varchar,'E003'::varchar,'Active'::varchar,inactive_date) from atlas_acceptance.hr_employment_end where tenant='atlas' and employee_id='E003'").splitlines()[-1] == "Active", "Restored original-cutoff withdrawal effect differs")
                else:
                    expect(status == 403, "Restored private employment end denial failed")
                    status, _ = request(session, "/api/v1/resignations/" + resignation_id + "/employment-end/rescheduling")
                    expect(status == 403, "Restored replacement history privacy failed")
                status, rehire = request(session, "/api/v1/employees/E-REHIRE-TEST/rehire")
                if role == "HR":
                    expect(status == 200 and rehire["legacyAccessBlocked"] and rehire["employmentStatus"] == "Ended", "Restored future rehire/access hold differs")
                    expect([r["status"] for r in rehire["history"]] == ["REJECTED", "APPROVED"] and len(rehire["episodes"]) == 2 and rehire["episodes"][1]["state"] == "SCHEDULED", "Restored rehire review/episodes differ")
                    expect(rehire["history"][-1]["reviewer"] == "olivia", "Restored independent rehire reviewer differs")
                    expect(int(sql(target, "select count(*) from atlas_acceptance.hr_rehire_handoff where status='PENDING' and rehire_id in(select id from atlas_acceptance.hr_rehire where employee_id='E-REHIRE-TEST')")) == 3, "Restored rehire pending handoffs differ")
                    expect(sql(target, "set search_path=atlas_acceptance; select hr_employment_status('atlas'::varchar,'E-REHIRE-TEST'::varchar,'Active'::varchar,start_date) from hr_employment_episode where tenant='atlas' and employee_id='E-REHIRE-TEST'").splitlines()[-1] == "Onboarding", "Restored rehire start projection differs")
                    status, profile = request(session, "/api/v1/employees/E-REHIRE-TEST")
                    expect(status == 200 and profile["status"] == "Ended", "Restored pre-rehire profile differs")
                else:
                    expect(status == 403, "Restored private rehire denial failed")
                status, activation = request(session, "/api/v1/employees/E-RETURN-TEST/rehire/activation")
                if role == "HR":
                    expect(status == 200 and activation["employmentStatus"] == "Active", "Restored completed rehire status differs")
                    expect([a["status"] for a in activation["history"]] == ["REJECTED", "APPROVED"], "Restored completion decision history differs")
                    expect(activation["completion"]["subject"] == "returning" and activation["completion"]["role"] == "EMPLOYEE", "Restored fresh least-privilege grant differs")
                    expect(activation["history"][-1]["reviewer"] == "olivia", "Restored completion independence differs")
                    status, episodes = request(session, "/api/v1/employees/E-RETURN-TEST/rehire")
                    expect(status == 200 and episodes["episodes"][1]["state"] == "ACTIVE", "Restored active episode differs")
                else:
                    expect(status == 403, "Restored private completion denial failed")
                status, access = request(session, "/api/v1/employees/E-RETURN-TEST/rehire/access")
                if role == "HR":
                    expect(status == 200 and access["accessStatus"] == "REVOKED" and access["employmentStatus"] == "Active", "Restored local revocation/status differs")
                    expect(access["revocation"]["actor"] == "amelia" and access["revocation"]["subject"] == "returning-alias" and access["revocation"]["newVersion"] == access["revocation"]["inputVersion"] + 1, "Restored private revocation history differs")
                    expect(access["employeeVersion"] == access["revocation"]["newVersion"], "Restored revocation version differs")
                    expect(sql(target, "select status from atlas_acceptance.hr_rehire_handoff_current h join atlas_acceptance.hr_rehire r on r.tenant=h.tenant and r.id=h.rehire_id where r.employee_id='E-RETURN-TEST' and h.target='IDENTITY_REVIEW'") == "LOCAL_ACCESS_REVOKED", "Restored identity handoff differs")
                else:
                    expect(status == 403, "Restored private revocation context denial failed")
                status, replacement = request(session, "/api/v1/employees/E-RETURN-TEST/rehire/access/replacement")
                if role == "HR":
                    expect(status == 200 and replacement["grant"]["grantNumber"] == 2 and replacement["grant"]["subject"] == "returning-alias" and replacement["accessStatus"] == "REVOKED" and not replacement["canRequest"], "Restored replacement current state differs")
                    expect([r["status"] for r in replacement["history"]] == ["REJECTED", "APPROVED"] and replacement["history"][-1]["reviewer"] == "olivia", "Restored replacement review history differs")
                    expect([g["subject"] for g in replacement["grants"]] == ["returning", "returning-alias"] and [r["subject"] for r in replacement["revocations"]] == ["returning", "returning-alias"], "Restored private access history differs")
                    expect(int(sql(target, "select count(*) from atlas_acceptance.hr_local_access_grant where employee_id='E-RETURN-TEST'")) == 2 and int(sql(target, "select count(*) from atlas_acceptance.hr_rehire_access_revocation where employee_id='E-RETURN-TEST'")) == 2, "Restored original/replacement grant and hold counts differ")
                else:
                    expect(status == 403, "Restored replacement history privacy failed")
                if role == "HR":
                    status, departments = request(session, "/api/v1/organisation/departments")
                    expect(status == 200 and len(departments) == int(sql(target, "select count(*) from atlas_acceptance.hr_department where tenant='atlas'")), "Restored department references differ")
                    status, department_audit = request(session, "/api/v1/organisation/history")
                    expect(status == 200 and len(department_audit) > 0, "Restored department audit unavailable")
                    status, assigned_profile = request(session, "/api/v1/employees/E003")
                    expect(status == 200 and isinstance(assigned_profile["positionCode"], str), "Restored employee position unavailable")
                    status, chart_branch = request(session, "/api/v1/organisation/chart?parentId=E003")
                    expect(status == 200 and chart_branch["parent"]["id"] == "E003" and chart_branch["parent"]["positionCode"] == assigned_profile["positionCode"], "Restored chart effective node differs")
                    expect(set(chart_branch["parent"]) == allowed_node_fields, "Restored branch exposes private fields")

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
            assigned = client()
            _, token = request(assigned, "/api/csrf")
            credentials = urllib.parse.urlencode({"username": "marcus", "password": "atlas-demo"}).encode()
            expect(request(assigned, "/api/login", credentials, {token["headerName"]: token["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 204, "Assigned employee sign-in failed")
            status, own_tasks = request(assigned, "/api/v1/exit-tasks?status=COMPLETED&size=100")
            expect(status == 200 and own_tasks["total"] == int(sql(target, "select count(*) from atlas_acceptance.hr_exit_task_current where tenant='atlas' and assignee_id='E002' and status='COMPLETED'")) and own_tasks["total"] >= 2, "Restored assigned completion records differ")
            task = own_tasks["items"][0]["task"]
            expect(all(item["task"]["assigneeId"] == "E002" and not item["overdue"] for item in own_tasks["items"]), "Restored assignee/status projection differs")
            status, task_history = request(assigned, "/api/v1/exit-tasks/" + task["id"] + "/history")
            expect(status == 200 and len(task_history) == 2 and task_history[-1]["newStatus"] == "COMPLETED", "Restored task audit differs")
            expect(request(assigned, "/api/v1/resignations/" + task["requestId"] + "/history")[0] == 403, "Assigned task grants private resignation access")
            expect(request(assigned, "/api/v1/resignations/" + task["requestId"] + "/tasks")[0] == 403, "Assigned task grants whole case access")
            expect(request(assigned, "/api/v1/exit-tasks/" + managed_id + "/history")[0] == 403, "Former assignee retained task history access")
            _, token = request(assigned, "/api/csrf")
            expect(request(assigned, "/api/logout", b"", {token["headerName"]: token["token"]})[0] == 204 and request(assigned, "/api/v1/session")[0] == 401, "Assigned session logout failed")
            for user in ["returning", "returning-alias"]:
                returning = client()
                _, token = request(returning, "/api/csrf")
                credentials = urllib.parse.urlencode({"username": user, "password": "atlas-demo"}).encode()
                expect(request(returning, "/api/login", credentials, {token["headerName"]: token["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 401, "Restored revoked/alias identity gained access")
                expect(request(returning, "/api/v1/session")[0] == 401, "Restored revoked identity retained a session")
            expect(projection_manifest(target) == projections_before, "Restored API changed task projection")
            if verify_legal_name:
                supplied = sql(target, "set search_path=atlas_acceptance; select hr_employment_value('atlas'::varchar,'E003'::varchar,'legalName'::varchar,''::varchar,(now() at time zone 'UTC')::date)").splitlines()[-1]
                expect(bool(supplied), "Restored approved legal-name fixture required")
                for user, private in [("amelia", True), ("sophia", True), ("marcus", False)]:
                    reader = client()
                    _, token = request(reader, "/api/csrf")
                    credentials = urllib.parse.urlencode({"username": user, "password": "atlas-demo"}).encode()
                    expect(request(reader, "/api/login", credentials, {token["headerName"]: token["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 204, "Private-name reader sign-in failed")
                    status, profile = request(reader, "/api/v1/employees/E003")
                    expect(status == 200 and profile["legalName"] == (supplied if private else None), "Restored legal-name field permission differs")
                    status, history = request(reader, "/api/v1/employees/E003/history")
                    expect(status == 200 and any(a["field"] == "legalName" for a in history) == private, "Restored legal-name audit permission differs")
                    _, token = request(reader, "/api/csrf")
                    expect(request(reader, "/api/logout", b"", {token["headerName"]: token["token"]})[0] == 204, "Private-name reader logout failed")
                report["legalNamePrivacy"] = {"status": "PASSED", "roles": ["HR", "self", "manager"], "scope": "private profile and history on actual restored API; no names in report"}
            if verify_additional_contacts:
                contact_path = "/api/v1/employees/E003/additional-contacts"
                private_context = None
                for user, private in [("amelia", True), ("sophia", True), ("marcus", False), ("outsider", False)]:
                    reader = client()
                    _, token = request(reader, "/api/csrf")
                    credentials = urllib.parse.urlencode({"username": user, "password": "atlas-demo"}).encode()
                    expect(request(reader, "/api/login", credentials, {token["headerName"]: token["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 204, "Contact reader sign-in failed")
                    status, context = request(reader, contact_path)
                    expect(status == (200 if private else 403), "Restored contact permission differs")
                    if private:
                        expect(len(context["contacts"]) == 2 and context["requests"]["total"] == 2, "Restored current/future contact fixture differs")
                        if private_context is not None:
                            expect(context == private_context, "Restored self and HR contact snapshots differ")
                        private_context = context
                    _, token = request(reader, "/api/csrf")
                    expect(request(reader, "/api/logout", b"", {token["headerName"]: token["token"]})[0] == 204, "Contact reader logout failed")
                report["additionalContactPrivacy"] = {"status": "PASSED", "roles": ["HR", "self", "manager", "outsider"], "scope": "private current and future-approved snapshots/history on actual restored API; no contact values in report"}
            if verify_contact_inbox:
                for user in ["amelia", "olivia", "sophia", "marcus", "outsider"]:
                    reader = client()
                    _, token = request(reader, "/api/csrf")
                    credentials = urllib.parse.urlencode({"username": user, "password": "atlas-demo"}).encode()
                    expect(request(reader, "/api/login", credentials, {token["headerName"]: token["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 204, "Queue reader sign-in failed")
                    if verify_reference_history:
                        for kind, table, early_code in [("DEPARTMENT","hr_department_audit","BC-D000"),("POSITION","hr_position_audit","BC-P000")]:
                            history_status, journal = request(reader, "/api/v1/organisation/reference-history?kind="+kind+"&size=50")
                            permitted = user in ["amelia","olivia"]
                            expect(history_status == (200 if permitted else 403), "Restored reference history HR scope differs")
                            if permitted:
                                expected_events = json.loads(sql(target, "select coalesce(json_agg(json_build_object('id',id::text,'code',code,'actor',actor,'reason',reason,'recordedAt',to_char(recorded_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),'effectiveFrom',effective_from,'correlationId',correlation_id) order by recorded_at desc,id desc),'[]') from atlas_acceptance."+table+" where tenant='atlas'"))
                                expect(100 < len(expected_events) <= 10000, "Restored history fixture must include older retained events")
                                expected_ids = [v["id"] for v in expected_events]
                                actual_events = []
                                history_page = 0
                                boundary = journal["throughId"]
                                while True:
                                    expect(journal["kind"] == kind and journal["page"] == history_page and journal["total"] == len(expected_events) and journal["throughId"] == boundary, "Restored history metadata differs")
                                    actual_events.extend(journal["items"])
                                    if len(actual_events) >= journal["total"]:
                                        break
                                    expect(bool(journal["items"]), "Restored history did not advance")
                                    history_page += 1
                                    history_status, journal = request(reader, "/api/v1/organisation/reference-history?kind="+kind+"&size=50&page="+str(history_page)+"&throughId="+boundary)
                                    expect(history_status == 200, "Restored history continuation denied")
                                expect([v["id"] for v in actual_events] == expected_ids, "Restored complete history order/IDs differ")
                                for actual, expected in zip(actual_events,expected_events):
                                    actual_time = datetime.fromisoformat(actual["recordedAt"].replace("Z","+00:00"))
                                    expected_time = datetime.fromisoformat(expected["recordedAt"].replace("Z","+00:00"))
                                    expect(actual_time == expected_time and {k:v for k,v in actual.items() if k!="recordedAt"} == {k:v for k,v in expected.items() if k!="recordedAt"}, "Restored history provenance differs")
                                history_status, early = request(reader, "/api/v1/organisation/reference-history?kind="+kind+"&code="+early_code)
                                expect(history_status == 200 and early["total"] == 1 and early["items"][0]["code"] == early_code, "Restored older reference event missing")
                    if verify_benefit_history:
                        table, early_code = "benefit_plan_audit", "BE-H000"
                        history_status, journal = request(reader, "/api/v1/benefits/creation-history?size=50")
                        permitted = user in ["amelia","olivia"]
                        expect(history_status == (200 if permitted else 403), "Restored benefit history HR scope differs")
                        if permitted:
                            expected_events = json.loads(sql(target, "select coalesce(json_agg(json_build_object('id',id::text,'code',code,'actor',actor,'reason',reason,'recordedAt',to_char(recorded_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),'effectiveFrom',effective_from,'correlationId',correlation_id) order by recorded_at desc,id desc),'[]') from atlas_acceptance."+table+" where tenant='atlas'"))
                            expect(100 < len(expected_events) <= 10000, "Restored history fixture must include older retained events")
                            expected_ids = [v["id"] for v in expected_events]
                            actual_events = []
                            history_page = 0
                            boundary = journal["throughId"]
                            while True:
                                expect(journal["page"] == history_page and journal["total"] == len(expected_events) and journal["throughId"] == boundary, "Restored history metadata differs")
                                actual_events.extend(journal["items"])
                                if len(actual_events) >= journal["total"]:
                                    break
                                expect(bool(journal["items"]), "Restored history did not advance")
                                history_page += 1
                                history_status, journal = request(reader, "/api/v1/benefits/creation-history?size=50&page="+str(history_page)+"&throughId="+boundary)
                                expect(history_status == 200, "Restored history continuation denied")
                            expect([v["id"] for v in actual_events] == expected_ids, "Restored complete history order/IDs differ")
                            for actual, expected in zip(actual_events,expected_events):
                                actual_time = datetime.fromisoformat(actual["recordedAt"].replace("Z","+00:00"))
                                expected_time = datetime.fromisoformat(expected["recordedAt"].replace("Z","+00:00"))
                                expect(actual_time == expected_time and {k:v for k,v in actual.items() if k!="recordedAt"} == {k:v for k,v in expected.items() if k!="recordedAt"}, "Restored history provenance differs")
                            history_status, early = request(reader, "/api/v1/benefits/creation-history?code="+early_code)
                            expect(history_status == 200 and early["total"] == 1 and early["items"][0]["code"] == early_code, "Restored older benefit event missing")
                    if verify_benefit_catalogue:
                        tenant = "other" if user == "outsider" else "atlas"
                        expected_plans = json.loads(sql(target, "select coalesce(json_agg(json_build_object('code',code,'name',name,'currency',currency,'monthlyContribution',to_char(monthly_contribution,'FM999999990.00'),'minimumServiceDays',minimum_service_days,'effectiveFrom',effective_from,'ruleVersion',rule_version) order by name collate \"C\",code collate \"C\"),'[]') from atlas_acceptance.benefit_plan where tenant='"+tenant+"'"))
                        expect((tenant == "other" and len(expected_plans) == 0) or (tenant == "atlas" and 1000 < len(expected_plans) <= 10000), "Complete restored catalogue fixture bounds differ")
                        actual_plans = []
                        for catalogue_page in range((len(expected_plans)+99)//100 or 1):
                            catalogue_status, catalogue = request(reader,"/api/v1/benefits/plans?size=100&page="+str(catalogue_page))
                            expect(catalogue_status == 200 and catalogue["total"] == len(expected_plans) and catalogue["page"] == catalogue_page and catalogue["size"] == 100, "Restored benefit catalogue page/count differs")
                            expect(catalogue["evaluatedOn"] == datetime.now(timezone.utc).date().isoformat(), "Restored benefit catalogue UTC evaluation differs")
                            actual_plans.extend(catalogue["items"])
                        expect([{k:v for k,v in plan.items() if k not in ["eligible","explanation","mode"]} for plan in actual_plans] == expected_plans, "Restored complete benefit catalogue values/order differ")
                        expect(all(plan["mode"] == "DEMONSTRATION" and isinstance(plan["eligible"], bool) and bool(plan["explanation"]) for plan in actual_plans), "Restored benefit policy presentation differs")
                        eligible_codes = [plan["code"] for plan in actual_plans if plan["eligible"]]
                        actual_eligible = []
                        for catalogue_page in range((len(eligible_codes)+99)//100 or 1):
                            catalogue_status, catalogue = request(reader,"/api/v1/benefits/plans?eligibility=ELIGIBLE&size=100&page="+str(catalogue_page))
                            expect(catalogue_status == 200 and catalogue["total"] == len(eligible_codes) and catalogue["page"] == catalogue_page and catalogue["size"] == 100, "Restored eligible benefit catalogue count differs")
                            expect(all(plan["eligible"] for plan in catalogue["items"]), "Restored eligible filter contains ineligible plan")
                            actual_eligible.extend(plan["code"] for plan in catalogue["items"])
                        expect(actual_eligible == eligible_codes, "Restored complete eligible plan order differs")
                        scoped_status, scoped_plan = request(reader,"/api/v1/benefits/plans?search=BF-C1004&eligibility=ELIGIBLE")
                        expect(scoped_status == 200 and scoped_plan["total"] == (1 if "BF-C1004" in eligible_codes else 0), "Restored older-cap boundary plan search differs")
                    if verify_benefit_inbox:
                        context = json.loads(sql(target,"select json_build_object('tenant',tenant,'employeeId',employee_id,'role',role) from atlas_acceptance.identity_membership where subject='"+user+"'"))
                        for queue_scope in ["OWN","REVIEW"]:
                            for queue_status in ["ALL","PENDING","APPROVED","REJECTED","WITHDRAWN"]:
                                query = "/api/v1/benefits/selection-inbox?scope="+queue_scope+"&status="+queue_status+"&size=50"
                                queue_http, queue = request(reader, query)
                                permitted = queue_scope == "OWN" or context["role"] == "HR"
                                expect(queue_http == (200 if permitted else 403), "Restored benefit queue role scope differs")
                                if not permitted:
                                    continue
                                condition = "tenant='"+context["tenant"]+"'"
                                if queue_scope == "OWN":
                                    condition += " and employee_id='"+context["employeeId"]+"'"
                                else:
                                    condition += " and employee_id<>'"+context["employeeId"]+"' and requested_by<>'"+user+"'"
                                if queue_status != "ALL":
                                    condition += " and status='"+queue_status+"'"
                                expected_ids = json.loads(sql(target,"select coalesce(json_agg(id order by recorded_at desc,id desc),'[]') from atlas_acceptance.benefit_selection where "+condition))
                                expect(len(expected_ids) <= 10000, "Restored benefit inbox fixture workload exceeded")
                                queue_rows=[]
                                queue_page=0
                                while True:
                                    expect(queue["total"] == len(expected_ids) and queue["page"] == queue_page and queue["size"] == 50, "Restored benefit inbox metadata differs")
                                    queue_rows.extend(queue["items"])
                                    if len(queue_rows) >= queue["total"]:
                                        break
                                    expect(bool(queue["items"]), "Restored benefit inbox did not advance")
                                    queue_page+=1
                                    queue_http, queue = request(reader,query+"&page="+str(queue_page))
                                    expect(queue_http == 200, "Restored benefit inbox continuation denied")
                                expect([row["id"] for row in queue_rows] == expected_ids, "Restored complete benefit inbox IDs/order differ")
                                for row in queue_rows:
                                    expect(re.fullmatch(r"[a-f0-9-]{36}",row["id"]) is not None, "Invalid restored benefit identifier")
                                    saved = json.loads(sql(target,"select json_build_object('employeeId',employee_id,'planCode',plan_code,'planName',plan_name,'currency',currency,'monthlyContribution',to_char(monthly_contribution,'FM999999990.00'),'ruleVersion',rule_version,'effectiveDate',effective_date,'requestedBy',requested_by,'reason',reason,'status',status,'version',version,'decidedBy',decided_by,'decisionReason',decision_reason) from atlas_acceptance.benefit_selection where tenant='"+context["tenant"]+"' and id='"+row["id"]+"'"))
                                    expect({k:row[k] for k in saved} == saved, "Restored benefit selection snapshot differs")
                                    dependants = json.loads(sql(target,"select coalesce(json_agg(json_build_object('name',name,'relationship',relationship) order by ordinal),'[]') from atlas_acceptance.benefit_dependant where tenant='"+context["tenant"]+"' and selection_id='"+row["id"]+"'"))
                                    expect(row["dependants"] == dependants, "Restored private benefit dependants differ")
                                    audit_count=int(sql(target,"select count(*) from atlas_acceptance.benefit_selection_audit where tenant='"+context["tenant"]+"' and selection_id='"+row["id"]+"'"))
                                    expect(len(row["history"]) == audit_count and all(bool(event["correlationId"]) for event in row["history"]), "Restored benefit queue history differs")
                    if verify_department_parents:
                        member = json.loads(sql(target, "select json_build_object('tenant',tenant,'role',role) from atlas_acceptance.identity_membership where subject='"+user+"'"))
                        expect(member["tenant"] in ["atlas","other"], "Unexpected parent fixture tenant")
                        codes = json.loads(sql(target,"select coalesce(json_agg(code order by code),'[]') from (select distinct code from atlas_acceptance.hr_department_parent_change where tenant='atlas') c"))
                        expect(bool(codes) and len(codes)<=100, "Parent correction fixtures missing or checker bound exceeded")
                        for code in codes:
                            expect(re.fullmatch(r"[A-Z0-9-]{2,30}",code) is not None, "Invalid checker-owned department code")
                            url = "/api/v1/organisation/departments/"+code+"/hierarchy?size=10"
                            status, parent_context = request(reader,url)
                            permitted = member["role"] == "HR" and member["tenant"] == "atlas"
                            expect(status == (200 if permitted else 403), "Restored parent configuration scope differs")
                            if not permitted:
                                continue
                            evaluated = parent_context["evaluatedOn"]
                            expect(evaluated == datetime.now(timezone.utc).date().isoformat(), "Restored hierarchy UTC date differs")
                            expected = json.loads(sql(target,"select coalesce(json_agg(json_build_object('code',code,'version',version,'oldParentCode',old_parent_code,'parentCode',parent_code,'effectiveDate',effective_date,'actor',actor,'reason',reason,'idempotencyKey',idempotency_key,'correlationId',correlation_id) order by version desc),'[]') from atlas_acceptance.hr_department_parent_change where tenant='atlas' and code='"+code+"'"))
                            actual = []; page_number = 0
                            while True:
                                expect(parent_context["total"] == len(expected) and parent_context["page"] == page_number and parent_context["size"] == 10, "Restored hierarchy history count/page differs")
                                actual.extend(parent_context["items"])
                                if len(actual)>=len(expected): break
                                expect(bool(parent_context["items"]), "Restored parent history did not advance")
                                page_number += 1
                                status,parent_context = request(reader,url+"&page="+str(page_number))
                                expect(status == 200, "Restored parent history continuation failed")
                            expect([{k:v for k,v in row.items() if k!='recordedAt'} for row in actual] == expected, "Restored complete parent correction provenance differs")
                            for row in actual:
                                recorded = sql(target,"select extract(epoch from recorded_at) from atlas_acceptance.hr_department_parent_change where tenant='atlas' and code='"+code+"' and version="+str(row["version"]))
                                expect(abs(datetime.fromisoformat(row["recordedAt"].replace('Z','+00:00')).timestamp()-float(recorded))<0.000001, "Restored parent recording instant differs")
                            current = sql(target,"set search_path=atlas_acceptance,pg_catalog; select coalesce(atlas_acceptance.hr_department_parent('atlas','"+code+"',date '"+evaluated+"'),'')", quiet=True)
                            expect((parent_context["department"]["parentCode"] or '')==current and parent_context["department"]["version"] == expected[0]["version"], "Restored current parent/revision differs")
                    if verify_department_directory:
                        tenant = "other" if user == "outsider" else "atlas"
                        catalogue_status, catalogue = request(reader, "/api/v1/organisation/department-directory?size=50")
                        expect(catalogue_status == 200, "Restored department catalogue unavailable")
                        evaluated = catalogue["evaluatedOn"]
                        expect(evaluated == datetime.now(timezone.utc).date().isoformat(), "Restored catalogue UTC date differs")
                        # Tenant is a checker-owned fixture constant, never client input.
                        expected_departments = json.loads(sql(target, "set search_path=atlas_acceptance,pg_catalog; with projected as materialized(select d.*,atlas_acceptance.hr_department_parent(d.tenant,d.code,date '"+evaluated+"') resolved_parent from atlas_acceptance.hr_department d where d.tenant='"+tenant+"') select coalesce(json_agg(json_build_object('code',d.code,'name',d.name,'parentCode',d.resolved_parent,'parentName',(select p.name from projected p where p.code=d.resolved_parent),'costCentre',d.cost_centre,'effectiveFrom',d.effective_from,'timing',case when d.effective_from<=date '"+evaluated+"' then 'CURRENT' else 'PLANNED' end,'directChildren',(select count(*) from projected c where c.resolved_parent=d.code)) order by d.name asc,d.code asc),'[]') from projected d", quiet=True))
                        expect(len(expected_departments) <= 10000, "Restored catalogue checker workload exceeded")
                        actual_departments = []
                        page_number = 0
                        while True:
                            expect(catalogue["total"] == len(expected_departments) and catalogue["evaluatedOn"] == evaluated and catalogue["page"] == page_number and catalogue["size"] == 50, "Restored catalogue page/count/date differs")
                            actual_departments.extend(catalogue["items"])
                            if len(actual_departments) >= catalogue["total"]:
                                break
                            expect(bool(catalogue["items"]), "Restored catalogue did not advance")
                            page_number += 1
                            catalogue_status, catalogue = request(reader, "/api/v1/organisation/department-directory?size=50&page="+str(page_number))
                            expect(catalogue_status == 200, "Restored catalogue continuation failed")
                        expect(actual_departments == expected_departments, "Restored catalogue complete IDs/order/parent/count/status differs")
                        position_status, listing = request(reader, "/api/v1/organisation/positions?size=100")
                        expect(position_status == 200 and listing["total"] <= 10000, "Restored position listing fixture exceeds checker bound")
                        expected_positions = json.loads(sql(target, "select coalesce(json_agg(json_build_object('code',p.code,'title',p.title,'departmentCode',p.department_code,'departmentName',d.name,'effectiveFrom',p.effective_from) order by p.title asc,p.code asc),'[]') from atlas_acceptance.hr_position p join atlas_acceptance.hr_department d on d.tenant=p.tenant and d.code=p.department_code where p.tenant='"+tenant+"'"))
                        actual_positions = list(listing["items"])
                        position_page = 0
                        while len(actual_positions) < listing["total"]:
                            expect(bool(listing["items"]), "Restored position listing did not advance")
                            position_page += 1
                            position_status, listing = request(reader, "/api/v1/organisation/positions?size=100&page="+str(position_page))
                            expect(position_status == 200 and listing["total"] == len(expected_positions), "Restored position continuation/count differs")
                            actual_positions.extend(listing["items"])
                        expect(actual_positions == expected_positions and listing["total"] == len(expected_positions), "Restored position listing exact projection/name/tenant differs")
                    if verify_audit_pagination:
                        profile_status, audit_profile = request(reader, "/api/v1/employees/E003")
                        audit_status, audit_page = request(reader, "/api/v1/employees/E003/change-history?size=50")
                        expect(audit_status == (200 if profile_status == 200 else 403), "Restored audit history record scope differs")
                        if profile_status == 200:
                            known_fields = {"phone","name","title","department","location","positionCode","calendarCode","legalEntityCode","managerId","legalName","birthDate","personalEmail","address","emergencyContactName","emergencyContactRelationship","emergencyContactPhone"}
                            private_fields = {"legalName","birthDate","legalEntityCode","personalEmail","address","emergencyContactName","emergencyContactRelationship","emergencyContactPhone"}
                            expected_fields = known_fields if user in ["amelia","olivia","sophia"] else known_fields-private_fields
                            expect(audit_page["fields"] == sorted(expected_fields), "Restored audit field options exceed current permission")
                            sql_fields = ",".join("'"+f+"'" for f in sorted(expected_fields))
                            expected_ids = json.loads(sql(target, "select coalesce(json_agg(id::text order by recorded_at desc,id desc),'[]') from atlas_acceptance.hr_audit where tenant='atlas' and employee_id='E003' and field in("+sql_fields+")"))
                            expect(len(expected_ids) <= 10000 and audit_page["total"] == len(expected_ids), "Restored scoped audit total differs")
                            actual_ids = []
                            while True:
                                expect(audit_page["total"] == len(expected_ids) and all(h["field"] in expected_fields for h in audit_page["items"]), "Restored audit page privacy/count differs")
                                actual_ids.extend(h["id"] for h in audit_page["items"])
                                next_cursor = audit_page["nextCursor"]
                                if not next_cursor:
                                    break
                                expect(len(actual_ids) <= len(expected_ids), "Restored audit cursor did not advance")
                                audit_status, audit_page = request(reader, "/api/v1/employees/E003/change-history?size=50&cursor="+urllib.parse.quote(next_cursor,safe=""))
                                expect(audit_status == 200, "Restored audit continuation failed")
                            expect(actual_ids == expected_ids and len(set(actual_ids)) == len(actual_ids), "Restored full audit order/coverage differs")
                            birth_status, old_birth_events = request(reader, "/api/v1/employees/E003/change-history?field=birthDate&size=50")
                            if user in ["amelia","olivia","sophia"]:
                                source_births = int(sql(target, "select count(*) from atlas_acceptance.hr_audit where tenant='atlas' and employee_id='E003' and field='birthDate'"))
                                expect(len(expected_ids)>100 and birth_status == 200 and old_birth_events["total"] == source_births and len(old_birth_events["items"]) == source_births and source_births>0, "Restored older private history is incomplete")
                            else:
                                expect(birth_status == 403, "Restored manager private-field filter must be denied")
                    if verify_entity_assignment:
                        profile_status, entity_profile = request(reader, "/api/v1/employees/E003")
                        permitted_entity = user in ["amelia", "olivia", "sophia"]
                        expect(profile_status == (403 if user == "outsider" else 200), "Restored entity profile scope differs")
                        if profile_status == 200:
                            expect(entity_profile["legalEntityCode"] == ("AY-API" if permitted_entity else None), "Restored private entity reference differs")
                            status, entity_history = request(reader, "/api/v1/employees/E003/history")
                            expect(status == 200 and any(v["field"] == "legalEntityCode" for v in entity_history) == permitted_entity, "Restored entity audit privacy differs")
                            status, entity_directory = request(reader, "/api/v1/directory?search=E003")
                            expect(status == 200 and entity_directory["items"][0]["legalEntityCode"] == ("AY-API" if permitted_entity else None), "Restored entity directory privacy differs")
                        entity_request = sql(target, "select id from atlas_acceptance.hr_request where tenant='atlas' and employee_id='E003' and field='legalEntityCode' and new_value='AY-UI'")
                        expect(bool(entity_request), "Restored declared entity fixture missing")
                        context_status, entity_context = request(reader, "/api/v1/requests/" + entity_request + "/entity-context")
                        expect(context_status == (200 if user in ["amelia","olivia"] else 403), "Restored entity context HR scope differs")
                        if user in ["amelia","olivia"]:
                            expect(entity_context["reference"]["code"] == "AY-UI" and not entity_context["retirementBlocked"], "Restored declared source differs")
                            status, entity_frozen = request(reader, "/api/v1/legal-entities/AY-UI/retirement")
                            expect(status == 200 and entity_frozen["assignmentRecorded"], "Restored entity freeze must survive unassignment")
                    if verify_calendar_history:
                        profile_status, history_profile = request(reader, "/api/v1/employees/E003")
                        history_status, calendar_history = request(reader, "/api/v1/employees/E003/calendar-history?size=50")
                        expect(history_status == (200 if profile_status == 200 else 403), "Restored calendar history record scope differs")
                        if profile_status == 200:
                            expected_ids = [str(v) for v in json.loads(sql(target, "select coalesce(json_agg(version_id order by episode_start desc,effective_date desc,recorded_at desc,version_id desc),'[]') from atlas_acceptance.hr_calendar_assignment where tenant='atlas' and employee_id='E003'"))]
                            expect(10 <= len(expected_ids) <= 50 and calendar_history["total"] == len(expected_ids), "Restored calendar history fixture/count differs")
                            expect([v["versionId"] for v in calendar_history["items"]] == expected_ids, "Restored calendar history stable order differs")
                            expect(calendar_history["currentEpisodeStart"] == history_profile["startDate"], "Restored calendar history episode differs")
                            expect(all(set(v) == {"versionId","episodeStart","effectiveDate","recordedAt","calendarCode","calendarName","timeZone"} for v in calendar_history["items"]), "Restored calendar history exposes unexpected fields")
                            page_status, history_page = request(reader, "/api/v1/employees/E003/calendar-history?size=8&page=1")
                            expect(page_status == 200 and [v["versionId"] for v in history_page["items"]] == expected_ids[8:16], "Restored calendar history second page differs")
                    if verify_employee_calendar:
                        status, assigned_profile = request(reader, "/api/v1/employees/E003")
                        current_date = datetime.now(timezone.utc).date().isoformat()
                        preview_status, assigned_preview = request(reader, "/api/v1/employees/E003/calendar-preview?date=" + current_date)
                        expect(preview_status == (200 if status == 200 else 403), "Restored assigned preview record scope differs")
                        if status == 200:
                            expect(assigned_profile["calendarCode"] == "AW-API" and assigned_preview["preview"]["code"] == "AW-API" and assigned_preview["preview"]["revision"] == 0 and assigned_preview["preview"]["working"] is True, "Restored current assignment/policy differs")
                            clear_date = sql(target, "select effective_date from atlas_acceptance.hr_request where tenant='atlas' and employee_id='E003' and field='calendarCode' and new_value='' and effective_date>(now() at time zone 'UTC')::date and status='APPROVED' order by effective_date limit 1")
                            clear_status = request(reader, "/api/v1/employees/E003/calendar-preview?date=" + clear_date)[0] if clear_date else None
                            expect(bool(clear_date) and clear_status == 409, "Restored effective unassignment must be unavailable, not a nonworking date; actual status=" + str(clear_status))
                        source_request = sql(target, "select id from atlas_acceptance.hr_request where tenant='atlas' and employee_id='E003' and field='calendarCode' and new_value='AW-UI'")
                        expect(bool(source_request), "Restored calendar request fixture missing")
                        review_status, assignment_review = request(reader, "/api/v1/requests/" + source_request + "/calendar-context")
                        permitted_review = user in ["amelia", "olivia"]
                        expect(review_status == (200 if permitted_review else 403), "Restored assignment context HR scope differs")
                        if permitted_review:
                            expect(assignment_review["expectedPolicyVersion"] == 1 and assignment_review["currentPolicyVersion"] == 1 and not assignment_review["policyChanged"], "Restored captured policy context differs")
                            status, frozen = request(reader, "/api/v1/company-calendars/AW-UI/amendments")
                            expect(status == 200 and frozen["assignmentRecorded"] is True and frozen["draft"] is None and frozen["retirementDraft"] is None, "Restored policy freeze must survive unassignment")
                    if verify_personal_email:
                        status, email_profile = request(reader, "/api/v1/employees/E003")
                        permitted_email = user in ["amelia", "olivia", "sophia"]
                        expect(status == (403 if user == "outsider" else 200), "Restored personal email profile scope differs")
                        if status == 200:
                            source_email = sql(target, "set search_path=atlas_acceptance; select hr_employment_value('atlas'::varchar,'E003'::varchar,'personalEmail'::varchar,''::varchar,(now() at time zone 'UTC')::date)").splitlines()[-1]
                            expect(bool(source_email) and email_profile["personalEmail"] == (source_email if permitted_email else None), "Restored personal email privacy/value differs")
                            status, email_history = request(reader, "/api/v1/employees/E003/history")
                            expect(status == 200 and any(v["field"] == "personalEmail" for v in email_history) == permitted_email, "Restored personal email history privacy differs")
                            status, email_search = request(reader, "/api/v1/directory?search=" + urllib.parse.quote(source_email, safe=""))
                            expect(status == 200 and email_search["total"] == 0, "Restored private email must not be directory-searchable")
                    if verify_calendar_retirement:
                        permitted_retirement = user in ["amelia", "olivia"]
                        status, retirement_context = request(reader, "/api/v1/company-calendars/RET-UI/amendments")
                        expect(status == (200 if permitted_retirement else 403), "Restored retirement context permission differs")
                        if permitted_retirement:
                            source = json.loads(sql(target, "select json_build_object('version',calendar_version,'effectiveDate',effective_date,'createdBy',created_by,'reason',reason,'correlationId',correlation_id) from atlas_acceptance.hr_work_calendar_retirement where tenant='atlas' and code='RET-UI'"))
                            entry = retirement_context["retirement"]
                            expect(entry["retirement"]["version"] == source["version"] and entry["retirement"]["effectiveDate"] == source["effectiveDate"] and all(entry[k] == source[k] for k in ["createdBy","reason","correlationId"]), "Restored retirement provenance differs")
                            expect(retirement_context["retirementStatus"] == "SCHEDULED" and retirement_context["retirementDraft"] is None and retirement_context["draft"] is None, "Restored retirement command availability differs")
                            expect(request(reader, "/api/v1/company-calendars/RET-UI/preview?date=" + source["effectiveDate"])[0] == 409, "Restored retirement must be unavailable, not a nonworking day")
                            closure = retirement_context["history"][0]["revision"]["policy"]["closedDates"][0]
                            status, previous = request(reader, "/api/v1/company-calendars/RET-UI/preview?date=" + closure)
                            expect(status == 200 and previous["revision"] == 1 and previous["classification"] == "COMPANY_CLOSURE", "Restored pre-retirement history differs")
                            for availability,total in [("CURRENT",0),("RETIRED",1)]:
                                status, page = request(reader, "/api/v1/company-calendars?search=RET-NOW&status=" + availability)
                                expect(status == 200 and page["total"] == total, "Restored retirement availability differs")
                    if verify_calendar_amendments:
                        permitted_amendment = user in ["amelia", "olivia"]
                        status, amendment_context = request(reader, "/api/v1/company-calendars/AMEND-UI/amendments")
                        expect(status == (200 if permitted_amendment else 403), "Restored amendment context permission differs")
                        if permitted_amendment:
                            expected_revisions = json.loads(sql(target, "select coalesce(json_agg(json_build_object('version',ordinal,'effectiveDate',effective_date,'workingDays',working_days,'closedDates',closed_dates) order by ordinal),'[]') from atlas_acceptance.hr_work_calendar_revision where tenant='atlas' and code='AMEND-UI'"))
                            actual_revisions = [{"version":h["revision"]["version"],"effectiveDate":h["revision"]["effectiveDate"],"workingDays":h["revision"]["policy"]["workingDays"],"closedDates":h["revision"]["policy"]["closedDates"]} for h in amendment_context["history"]]
                            expect(expected_revisions and actual_revisions == expected_revisions and amendment_context["version"] == len(expected_revisions), "Restored amendment policy/history differs")
                            closure = expected_revisions[0]["closedDates"][0]
                            status, after_amendment = request(reader, "/api/v1/company-calendars/AMEND-UI/preview?date=" + closure)
                            expect(status == 200 and after_amendment["revision"] == 1 and after_amendment["classification"] == "COMPANY_CLOSURE", "Restored dated closure differs")
                            base_date = amendment_context["base"]["calendar"]["effectiveFrom"]
                            status, before_amendment = request(reader, "/api/v1/company-calendars/AMEND-UI/preview?date=" + base_date)
                            expect(status == 200 and before_amendment["revision"] == 0 and before_amendment["classification"] == "WORKING_DAY", "Restored earlier policy changed")
                    if verify_company_calendars:
                        permitted_calendar = user in ["amelia", "olivia"]
                        status, company_calendars = request(reader, "/api/v1/company-calendars?size=50&tenant=forged")
                        expect(status == (200 if permitted_calendar else 403), "Restored company-calendar list permission differs")
                        status, calendar_entry = request(reader, "/api/v1/company-calendars/CAL-UI")
                        expect(status == (200 if permitted_calendar else 403), "Restored company-calendar detail permission differs")
                        if permitted_calendar:
                            expected_count = int(sql(target, "select count(*) from atlas_acceptance.hr_work_calendar where tenant='atlas'"))
                            expect(company_calendars["total"] == expected_count and len(company_calendars["items"]) == expected_count, "Restored calendar count differs")
                            source_calendar = json.loads(sql(target, "select json_build_object('code',code,'name',name,'timeZone',time_zone,'effectiveFrom',effective_from,'effectiveUntil',effective_until,'workingDays',working_days,'closedDates',closed_dates) from atlas_acceptance.hr_work_calendar where tenant='atlas' and code='CAL-UI'"))
                            expect(calendar_entry["calendar"] == source_calendar, "Restored calendar policy differs")
                            closure = source_calendar["closedDates"][0]
                            status, calendar_preview = request(reader, "/api/v1/company-calendars/CAL-UI/preview?date=" + closure)
                            expect(status == 200 and calendar_preview["classification"] == "COMPANY_CLOSURE" and calendar_preview["working"] is False, "Restored calendar classification differs")
                        else:
                            expect(request(reader, "/api/v1/company-calendars/CAL-UI/preview?date=2000-01-01")[0] == 403, "Restored calendar preview permission differs")
                    if verify_birth_date:
                        status, birth_profile = request(reader, "/api/v1/employees/E003")
                        permitted = user in ["amelia", "olivia", "sophia"]
                        expect(status == (403 if user == "outsider" else 200), "Restored birth-date profile scope differs")
                        if status == 200:
                            supplied_birth_date = sql(target, "set search_path=atlas_acceptance; select hr_employment_value('atlas'::varchar,'E003'::varchar,'birthDate'::varchar,''::varchar,(now() at time zone 'UTC')::date)").splitlines()[-1]
                            expect(birth_profile["birthDate"] == (supplied_birth_date if permitted else None), "Restored birth-date field privacy differs")
                            status, birth_history = request(reader, "/api/v1/employees/E003/history")
                            birth_window_count = int(sql(target, "select count(*) from (select a.field from atlas_acceptance.hr_audit a join atlas_acceptance.hr_request r on r.id=a.request_id and r.tenant=a.tenant where a.tenant='atlas' and a.employee_id='E003' order by a.recorded_at desc limit 100) h where field='birthDate'"))
                            total_birth_count = int(sql(target, "select count(*) from atlas_acceptance.hr_audit where tenant='atlas' and employee_id='E003' and field='birthDate'"))
                            expect(status == 200 and sum(a["field"] == "birthDate" for a in birth_history) == (birth_window_count if permitted else 0), "Restored birth-date history window/privacy differs")
                            report["birthDateHistoryWindow"] = {"limit":100,"sourceFieldRowsInWindow":birth_window_count,"retainedSourceFieldRows":total_birth_count,"scope":"deprecated legacy latest-100 window before field filtering; complete page API checks are reported separately when enabled"}
                    status, own = request(reader, "/api/v1/additional-contact-inbox?scope=OWN&status=ALL")
                    expect(status == 200, "Restored own queue unavailable")
                    if user == "sophia":
                        expect(own["total"] == 2 and all(r["employeeId"] == "E003" for r in own["items"]), "Restored own contact queue differs")
                    elif user in ["amelia", "marcus", "outsider"]:
                        expect(own["total"] == 0, "Restored own queue leaked another employee")
                    status, review = request(reader, "/api/v1/additional-contact-inbox?scope=REVIEW&status=ALL")
                    expect(status == (200 if user in ["amelia", "olivia"] else 403), "Restored HR queue permission differs")
                    if user in ["amelia", "olivia"]:
                        expect(any(r["employeeId"] == "E004" for r in review["items"]) == (user == "olivia"), "Restored independent-review scope differs")
                    if verify_retirement_inbox:
                        allowed = user in ["amelia", "olivia"]
                        employee_id = "E001" if user == "amelia" else "E005"
                        for queue_scope in ["OWN", "REVIEW"]:
                            status, retirement_queue = request(reader, "/api/v1/reference-retirement-inbox?status=ALL&size=50&scope=" + queue_scope + "&tenant=forged&subject=forged&employeeId=forged")
                            expect(status == (200 if allowed else 403), "Restored retirement inbox permission differs")
                            if allowed:
                                predicate = "requested_by='" + user + "' and requester_employee_id='" + employee_id + "'" if queue_scope == "OWN" else "requested_by<>'" + user + "' and requester_employee_id<>'" + employee_id + "'"
                                expected_ids = set(sql(target, "select id from atlas_acceptance.hr_legal_entity_retirement_request where tenant='atlas' and " + predicate).splitlines())
                                expect(len(expected_ids) <= 50 and retirement_queue["total"] == len(expected_ids) and {item["request"]["id"] for item in retirement_queue["items"]} == expected_ids, "Restored retirement inbox ownership differs")
                    if verify_retirement_notifications:
                        status, notifications = request(reader, "/api/v1/notifications")
                        expect(status == 200 and len(notifications) <= 50, "Restored notification read unavailable or unbounded")
                        # Users are the fixed synthetic reader list above; no caller-supplied SQL scope.
                        expected = json.loads(sql(target, "select coalesce(json_agg(message),'[]'::json) from (select message from atlas_acceptance.hr_notification where tenant=(select tenant from atlas_acceptance.identity_membership where subject='" + user + "') and subject='" + user + "' order by recorded_at desc,id desc limit 50) n"))
                        actual = [n["message"] for n in notifications]
                        expect(sorted(actual) == sorted(expected), "Restored private notification content/scope differs")
                        retirement_messages = [message for message in actual if "declared-reference retirement" in message]
                        if user not in ["amelia", "olivia"]:
                            expect(not retirement_messages, "Retirement prompt/outcome leaked to a non-HR recipient")
                        else:
                            retained = int(sql(target, "select count(*) from atlas_acceptance.hr_notification where tenant='atlas' and subject='" + user + "' and message like '%declared-reference retirement%'"))
                            expect(retained > 0, "Retained retirement notification fixture required")
                    _, token = request(reader, "/api/csrf")
                    expect(request(reader, "/api/logout", b"", {token["headerName"]: token["token"]})[0] == 204, "Queue reader logout failed")
                report["contactInboxPrivacy"] = {"status": "PASSED", "roles": ["author HR", "independent HR", "self", "manager", "outsider"], "scope": "OWN and independent REVIEW queue reads on actual restored API; values omitted"}
            if verify_reference_history:
                report["referenceCreationHistory"] = {"status":"PASSED","roles":["HR","independent HR","self denied","manager denied","other tenant denied"],"scope":"actual recovered complete department/position journal IDs/order and exact retained actor/reason/recorded/effective/correlation provenance beyond100; older code filters; no values in report"}

            if verify_benefit_history:
                report["benefitCreationHistory"] = {"status":"PASSED","roles":["HR","independent HR","self denied","manager denied","other tenant denied"],"scope":"actual recovered complete benefit journal IDs/order and exact retained actor/reason/recorded/effective/correlation provenance beyond100; older code filter; values omitted"}

            if verify_benefit_catalogue:
                report["benefitCatalogue"] = {"status":"PASSED","roles":["HR","independent HR","self","manager","other tenant"],"scope":"actual recovered complete catalogue beyond1000 with exact immutable values/order compared with owned SQL; eligible pages consistent with current domain-evaluated ALL results; direct boundary-plan search; values omitted"}

            if verify_benefit_inbox:
                report["benefitSelectionInbox"] = {"status":"PASSED","roles":["HR","independent HR","self","manager","other tenant"],"scope":"actual recovered OWN/REVIEW all-status counts/complete IDs/order and private snapshots/dependants versus owned SQL; history count/correlation; independent reviewer exclusions and role denial; values omitted"}

            if verify_timekeeping:
                from timekeeping_recovery import verify
                report["timekeeping"] = verify(target, client, request, expect)

            if verify_department_parents:
                report["departmentParentCorrections"] = {"status":"PASSED","roles":["HR","independent HR","self","manager","other tenant"],"scope":"actual recovered HR-only complete paginated old/new parent provenance, exact recording instants, current UTC effective parent/revision and public directory parent/count/name consistency versus owned restored SQL; values omitted"}
            if verify_department_directory:
                report["departmentDirectory"] = {"status":"PASSED","roles":["HR","independent HR","self","current manager","other tenant"],"scope":"actual restored full catalogue IDs/order, resolved parents, direct-child reference counts, current UTC timing and exact complete position listing/department names compared with owned PostgreSQL source; values omitted"}
            if verify_audit_pagination:
                report["employeeAuditPagination"] = {"status":"PASSED","roles":["HR","independent HR","self","current manager","other tenant"],"scope":"actual recovered complete authorised IDs/order beyond100, private-field options/totals/rows before paging, older birth-date access for self/HR and manager filter denial; legacy capped endpoint kept compatible; values omitted"}
            if verify_entity_assignment:
                report["entityAssignmentPrivacy"] = {"status":"PASSED","roles":["HR","independent HR","self","current manager","other tenant"],"scope":"actual recovered private current profile/directory/audit, HR-only immutable reference review and permanent retirement freeze after unassignment; values omitted"}
            if verify_calendar_history:
                report["calendarHistoryPrivacy"] = {"status":"PASSED","roles":["HR","independent HR","self","current manager","other tenant"],"scope":"actual recovered immutable assignment history, exact stable pages/current episode, authorised fields and record/tenant denial; values omitted"}
            if verify_employee_calendar:
                report["employeeCalendarPrivacy"] = {"status":"PASSED","roles":["HR","independent HR","self","current manager","other tenant"],"scope":"actual recovered current assignment, effective unassignment 409, HR-only policy snapshot review and persistent policy freeze after unassignment; values omitted"}
            if verify_personal_email:
                report["personalEmailPrivacy"] = {"status":"PASSED","roles":["HR","independent HR","self","manager","other tenant"],"scope":"actual recovered current private personal email and history, future value not applied early, no directory search leakage; values omitted"}
            if verify_calendar_retirement:
                report["calendarRetirementPrivacy"] = {"status":"PASSED", "roles":["HR","independent HR","self","manager","other tenant"], "scope":"actual restored provenance, command closure, retained earlier preview, unavailable retired preview and list availability; private values omitted"}
            if verify_calendar_amendments:
                report["calendarAmendmentPrivacy"] = {"status": "PASSED", "roles": ["HR", "independent HR", "self", "manager", "other tenant"], "scope": "actual recovered amendment context/snapshots and before/after date classification; non-HR denied; values omitted"}
            if verify_company_calendars:
                report["companyCalendarPrivacy"] = {"status": "PASSED", "roles": ["HR", "independent HR", "self", "manager", "other tenant"], "scope": "actual recovered complete calendar snapshot, scoped count, closure classification and non-HR denial; values omitted"}
            if verify_birth_date:
                report["recordedBirthDatePrivacy"] = {"status": "PASSED", "roles": ["HR", "independent HR", "self", "manager", "other tenant"], "scope": "actual recovered private profile and history matched retained versions; values omitted"}
            if verify_retirement_inbox:
                report["retirementInboxPrivacy"] = {"status": "PASSED", "roles": ["author HR", "reviewer HR", "self", "manager", "other tenant"], "scope": "actual recovered OWN/REVIEW IDs and totals match captured identity ownership; forged scope ignored and non-HR denied; values omitted"}
            if verify_retirement_notifications:
                report["retirementNotificationPrivacy"] = {"status": "PASSED", "roles": ["author HR", "reviewer HR", "self", "manager", "other tenant"], "scope": "retained notification rows and latest-50 actual recovered API content matched; non-HR retirement messages absent; message values omitted"}
            if verify_benefit_withdrawal:
                retained_withdrawal_ids = set(sql(target, "select id from atlas_acceptance.benefit_selection where tenant='atlas' and employee_id='E003' and status='WITHDRAWN'").splitlines())
                expect(bool(retained_withdrawal_ids), "Restored withdrawal fixture required")
                for user, private in [("amelia", True), ("sophia", True), ("marcus", False), ("outsider", False)]:
                    reader = client()
                    _, token = request(reader, "/api/csrf")
                    credentials = urllib.parse.urlencode({"username": user, "password": "atlas-demo"}).encode()
                    expect(request(reader, "/api/login", credentials, {token["headerName"]: token["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 204, "Withdrawal reader sign-in failed")
                    status, page = request(reader, "/api/v1/benefits/selections?size=100")
                    expect(status == 200, "Restored selection list unavailable")
                    found = {s["id"] for s in page["items"] if s["id"] in retained_withdrawal_ids}
                    expect(found == (retained_withdrawal_ids if private else set()), "Restored withdrawal record permission differs")
                    for selection in page["items"]:
                        if selection["id"] in retained_withdrawal_ids:
                            expect(selection["status"] == "WITHDRAWN" and selection["handoffStatus"] is None and len(selection["history"]) == 2 and selection["history"][-1]["action"] == "WITHDRAWN" and selection["history"][-1]["actor"] == selection["requestedBy"], "Restored withdrawal snapshot/audit differs")
                    _, token = request(reader, "/api/csrf")
                    expect(request(reader, "/api/logout", b"", {token["headerName"]: token["token"]})[0] == 204, "Withdrawal reader logout failed")
                report["benefitWithdrawalPrivacy"] = {"status": "PASSED", "roles": ["HR", "self", "manager", "outsider"], "scope": "retained WITHDRAWN snapshots, original owner audit and absent deduction on real restored API; no values in report"}
            if verify_postal_address:
                canonical = sql(target, "set search_path=atlas_acceptance; select hr_employment_value('atlas'::varchar,'E003'::varchar,'address'::varchar,''::varchar,(now() at time zone 'UTC')::date)").split("\n", 1)[-1]
                expect(canonical.startswith("@POSTAL/1\n"), "Restored structured address fixture required")
                expected = canonical.removeprefix("@POSTAL/1\n").split("\n")
                for user, private in [("amelia", True), ("sophia", True), ("marcus", False), ("outsider", False)]:
                    reader = client()
                    _, token = request(reader, "/api/csrf")
                    credentials = urllib.parse.urlencode({"username": user, "password": "atlas-demo"}).encode()
                    expect(request(reader, "/api/login", credentials, {token["headerName"]: token["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 204, "Postal reader sign-in failed")
                    status, context = request(reader, "/api/v1/employees/E003/postal-address")
                    expect(status == (200 if private else 403), "Restored postal address permission differs")
                    if private:
                        expect(context["legacyAddress"] is None and [context["address"][k] for k in ["line1", "line2", "locality", "region", "postalCode", "countryCode"]] == expected, "Restored typed postal address differs")
                    _, token = request(reader, "/api/csrf")
                    expect(request(reader, "/api/logout", b"", {token["headerName"]: token["token"]})[0] == 204, "Postal reader logout failed")
                report["postalAddressPrivacy"] = {"status": "PASSED", "roles": ["HR", "self", "manager", "outsider"], "scope": "typed private address on actual restored API; no address values in report"}
            if verify_legal_entities:
                for user, allowed in [("amelia", True), ("sophia", False), ("marcus", False), ("outsider", False)]:
                    reader = client()
                    _, token = request(reader, "/api/csrf")
                    credentials = urllib.parse.urlencode({"username": user, "password": "atlas-demo"}).encode()
                    expect(request(reader, "/api/login", credentials, {token["headerName"]: token["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 204, "Entity reader sign-in failed")
                    status, context = request(reader, "/api/v1/legal-entities?search=SYNTH&size=50")
                    expect(status == (200 if allowed else 403), "Restored legal-entity permission differs")
                    if allowed:
                        expected_codes = set(sql(target, "select code from atlas_acceptance.hr_legal_entity where tenant='atlas' and strpos(lower(code||' '||name),'synth')>0").splitlines())
                        expect({"SYNTH-GB", "SYNTH-FUTURE"}.issubset(expected_codes) and len(expected_codes) <= 50 and context["total"] == len(expected_codes) and {e["entity"]["code"] for e in context["items"]} == expected_codes, "Restored legal-entity fixtures differ")
                        expect(all(e["createdBy"] == "amelia" and e["correlationId"] and e["reason"] for e in context["items"]), "Restored legal-entity provenance missing")
                    if verify_reference_retirement:
                        status, context = request(reader, "/api/v1/legal-entities/PAGE-00/retirement")
                        expect(status == (200 if allowed else 403), "Restored retirement permission differs")
                        if allowed:
                            expect(context["version"] == 2 and len(context["requests"]) == 1 and context["requests"][0]["decision"]["actor"] == "olivia" and context["retirementDate"] is not None, "Restored approved retirement differs")
                            status, retired = request(reader, "/api/v1/legal-entities?search=PAGE-00&status=RETIRED")
                            expect(status == 200 and retired["total"] == 1 and "PAGE-00" in retired["retirementDates"], "Restored retired filter differs")
                            status, available = request(reader, "/api/v1/legal-entities?search=PAGE-01&status=CURRENT")
                            expect(status == 200 and available["total"] == 1 and "PAGE-01" in available["retirementDates"], "Restored scheduled retirement applied early")
                    _, token = request(reader, "/api/csrf")
                    expect(request(reader, "/api/logout", b"", {token["headerName"]: token["token"]})[0] == 204, "Entity reader logout failed")
                if verify_reference_retirement:
                    report["referenceRetirementPrivacy"] = {"status": "PASSED", "scope": "retained independent proposal/approval and immediate/future catalogue filters with HR-only context; no values in report"}
                report["legalEntityPrivacy"] = {"status": "PASSED", "roles": ["HR", "self", "manager", "outsider"], "scope": "immutable current/planned reference catalogue and creation provenance on actual recovered API; assignment checked separately when enabled"}
            after = manifest(target)
            expect({k:v for k,v in after.items() if k != "employee_document_audit"} == {k:v for k,v in before.items() if k != "employee_document_audit"}, "Application smoke changed restored business data")
            expect(after["employee_document_audit"]["rows"] == before["employee_document_audit"]["rows"] + 1, "Exactly one restored download audit required")
            retained_audit = sql(target, "select row_to_json(a)::text from atlas_acceptance.employee_document_audit a where id<=" + str(prior_document_audit_max) + " order by id")
            expect(hashlib.sha256(retained_audit.encode()).hexdigest() == prior_document_audit_digest, "Original restored document audit changed")
            expect(sql(target, "select action from atlas_acceptance.employee_document_audit where id>" + str(prior_document_audit_max)) == "DOWNLOAD_AUTHORISED", "Unexpected restored audit mutation")
            report["checks"] = ["readiness", "Flyway validation", "anonymous denial", "four identity mappings including assigned manager", "scoped counts", "scoped organisation roots/branch/effective node and minimal fields", "profile reads", "approved requests", "approval audit", "department references and audit", "position catalogue and audit", "employee position and approval history", "reporting candidates and approval history", "display-name approval history", "withdrawn request history", "benefits catalogue and audit", "benefit selections, audit and pending deduction", "independent private document storage, download integrity and new audit", "immutable rescan history and effective availability", "immutable document version chain and latest projection", "document templates, classification audit and latest completeness", "effective template revisions/retirement and correction history", "private retention/hold journal and independent release", "scoped resignations, private history and pending exit coordination", "private pending-resignation withdrawal receipt, matching terminal audit and unchanged status/access", "original case/end episode ownership with source/capture provenance and unrelated-user denial", "assigned exit tasks, private audit and no resignation access", "current task assignment/cancellation, HR-only journal and revoked former-assignee reads", "retained end/withdrawal and replacement history, current replacement date, original-cutoff Active and suppressed old/pending new targets", "approved hires", "rehire private review/history, preserved episodes, future start and legacy-access hold/pending handoffs", "onboarding status/history", "reviewed rehire completion retained, current revocation/private audit, Active employment and denied revoked/alias sign-in", "reviewed replacement history, latest grant/current hold and original plus replacement revocations", "record/tenant denial", "logout revocation", "unchanged business data"]
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
        report["readRateLimitRetries"] = READ_RATE_RETRIES
        report["totalSeconds"] = round(time.monotonic() - started, 3)
        evidence.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(run())
