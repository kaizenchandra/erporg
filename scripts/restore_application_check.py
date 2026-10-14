"""Start a separate local API against an already-verified synthetic restore."""
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
        before = manifest(target)
        expect(before == restore["tableManifest"], "Restore changed since its verification")
        with socket.socket() as sock:
            expect(sock.connect_ex(("127.0.0.1", 8081)) != 0, "Port 8081 is already occupied; no process will be stopped")
        with tempfile.TemporaryFile() as log:
            process = subprocess.Popen([
                "mvn", "-f", "backend/pom.xml", "-Dmaven.repo.local=/private/tmp/atlas-m2", "spring-boot:run", "-Dspring-boot.run.profiles=local",
                "-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8081 "
                f"--spring.datasource.url=jdbc:postgresql://localhost:5438/{target}?currentSchema=atlas_acceptance "
                "--spring.flyway.default-schema=atlas_acceptance --spring.flyway.schemas=atlas_acceptance", "-q"
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
                    status, hires = request(session, "/api/v1/hiring/requests?status=APPROVED")
                    expect(status == 200 and hires["total"] == int(sql(target, "select count(*) from atlas_acceptance.hr_hire_request where tenant='atlas' and status='APPROVED'")), "Restored approved hires differ")
                    status, inbox = request(session, "/api/v1/request-inbox?status=APPROVED")
                    expect(status == 200 and inbox["total"] == restore["approvedRequests"], "Restored approvals differ")
                    status, history = request(session, "/api/v1/employees/E003/history")
                    expect(status == 200 and any(item["action"] == "APPROVED" for item in history), "Restored approval audit unavailable")
                else:
                    expect(request(session, "/api/v1/employees/E001")[0] == 403, "Record/tenant denial failed")
                _, fresh = request(session, "/api/csrf")
                expect(request(session, "/api/logout", b"", {fresh["headerName"]: fresh["token"]})[0] == 204, "Logout failed")
                expect(request(session, "/api/v1/session")[0] == 401, "Logged-out session retained access")
            expect(manifest(target) == before, "Application smoke test changed restored business data")
            report["checks"] = ["readiness", "Flyway validation", "anonymous denial", "three identity mappings", "scoped counts", "profile reads", "approved requests", "approval audit", "department references and audit", "approved hires", "record/tenant denial", "logout revocation", "unchanged business data"]
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
