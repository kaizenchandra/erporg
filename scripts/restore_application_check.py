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
            returning = client()
            _, token = request(returning, "/api/csrf")
            credentials = urllib.parse.urlencode({"username": "returning", "password": "atlas-demo"}).encode()
            expect(request(returning, "/api/login", credentials, {token["headerName"]: token["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 204, "Restored fresh Employee sign-in failed")
            status, actor = request(returning, "/api/v1/session")
            expect(status == 200 and actor["role"] == "EMPLOYEE" and actor["employeeId"] == "E-RETURN-TEST", "Restored fresh Employee mapping differs")
            status, own = request(returning, "/api/v1/people-summary")
            expect(status == 200 and own["total"] == 1, "Restored returning scope differs")
            status, profile = request(returning, "/api/v1/employees/E-RETURN-TEST")
            expect(status == 200 and profile["status"] == "Active", "Restored returning profile differs")
            expect(request(returning, "/api/v1/employees/E001", headers={"X-Role":"HR"})[0] == 403, "Retained legacy HR role was restored")
            expect(request(returning, "/api/v1/employees/E-RETURN-TEST/rehire/activation")[0] == 403, "Returning Employee gained private HR context")
            _, token = request(returning, "/api/csrf")
            expect(request(returning, "/api/logout", b"", {token["headerName"]: token["token"]})[0] == 204 and request(returning, "/api/v1/session")[0] == 401, "Restored fresh session logout failed")
            alias = client()
            _, token = request(alias, "/api/csrf")
            credentials = urllib.parse.urlencode({"username": "returning-alias", "password": "atlas-demo"}).encode()
            expect(request(alias, "/api/login", credentials, {token["headerName"]: token["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 401, "Restored unselected alias gained access")
            expect(projection_manifest(target) == projections_before, "Restored API changed task projection")
            after = manifest(target)
            expect({k:v for k,v in after.items() if k != "employee_document_audit"} == {k:v for k,v in before.items() if k != "employee_document_audit"}, "Application smoke changed restored business data")
            expect(after["employee_document_audit"]["rows"] == before["employee_document_audit"]["rows"] + 1, "Exactly one restored download audit required")
            retained_audit = sql(target, "select row_to_json(a)::text from atlas_acceptance.employee_document_audit a where id<=" + str(prior_document_audit_max) + " order by id")
            expect(hashlib.sha256(retained_audit.encode()).hexdigest() == prior_document_audit_digest, "Original restored document audit changed")
            expect(sql(target, "select action from atlas_acceptance.employee_document_audit where id>" + str(prior_document_audit_max)) == "DOWNLOAD_AUTHORISED", "Unexpected restored audit mutation")
            report["checks"] = ["readiness", "Flyway validation", "anonymous denial", "four identity mappings including assigned manager", "scoped counts", "scoped organisation roots/branch/effective node and minimal fields", "profile reads", "approved requests", "approval audit", "department references and audit", "position catalogue and audit", "employee position and approval history", "reporting candidates and approval history", "display-name approval history", "withdrawn request history", "benefits catalogue and audit", "benefit selections, audit and pending deduction", "independent private document storage, download integrity and new audit", "immutable rescan history and effective availability", "immutable document version chain and latest projection", "document templates, classification audit and latest completeness", "effective template revisions/retirement and correction history", "private retention/hold journal and independent release", "scoped resignations, private history and pending exit coordination", "assigned exit tasks, private audit and no resignation access", "current task assignment/cancellation, HR-only journal and revoked former-assignee reads", "retained end/withdrawal and replacement history, current replacement date, original-cutoff Active and suppressed old/pending new targets", "approved hires", "rehire private review/history, preserved episodes, future start and legacy-access hold/pending handoffs", "onboarding status/history", "reviewed rehire completion, fresh Employee sign-in, retained legacy HR denial and unselected alias denial", "record/tenant denial", "logout revocation", "unchanged business data"]
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
