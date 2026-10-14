"""Two local synthetic API instances: session continuity, logout replay and expiry."""
import copy
import http.cookiejar
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from restore_application_check import request, expect
from restore_check import sql

SCHEMA = "atlas_session_check"


def stop(process):
    if process and process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def start(port, log):
    with socket.socket() as sock:
        expect(sock.connect_ex(("127.0.0.1", port)) != 0, "Test port already occupied")
    process = subprocess.Popen([
        "mvn", "-f", "backend/pom.xml", "-Dmaven.repo.local=/private/tmp/atlas-m2", "spring-boot:run", "-Dspring-boot.run.profiles=local",
        f"-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port={port} --spring.datasource.url=jdbc:postgresql://localhost:5438/atlas?currentSchema={SCHEMA} --spring.flyway.default-schema={SCHEMA} --spring.flyway.schemas={SCHEMA}", "-q"
    ], stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            expect(process.poll() is None, "Test API exited during startup")
            try:
                status, body = call(urllib.request.build_opener(), port, "/actuator/health/readiness")
                if status == 200 and body["status"] == "UP":
                    return process
            except OSError:
                pass
            time.sleep(0.5)
        raise RuntimeError("Session test API readiness timed out")
    except Exception:
        stop(process)
        raise


def call(client, port, path, data=None, headers=None):
    return request(client, path, data, headers, origin=f"http://127.0.0.1:{port}")


def login(port):
    cookies = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    _, csrf = call(client, port, "/api/csrf")
    data = urllib.parse.urlencode({"username": "sophia", "password": "atlas-demo"}).encode()
    expect(call(client, port, "/api/login", data, {csrf["headerName"]: csrf["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 204, "Synthetic login failed")
    return client, cookies


def run():
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "status": "FAILED", "scope": "local JDBC sessions across two Java processes; synthetic password authentication"}
    processes = []
    started = time.monotonic()
    try:
        with tempfile.TemporaryFile() as log:
            first = start(8082, log)
            processes.append(first)
            second = start(8083, log)
            processes.append(second)
            client, cookies = login(8082)
            expect(any(c.name == "JSESSIONID" and c.has_nonstandard_attr("HttpOnly") for c in cookies), "Expected HttpOnly session cookie")
            expect(call(client, 8083, "/api/v1/session")[0] == 200, "Session not visible to second instance")
            stop(first)
            first = start(8082, log)
            processes.append(first)
            expect(call(client, 8082, "/api/v1/session")[0] == 200, "Session did not survive restart")
            copied = http.cookiejar.CookieJar()
            for cookie in cookies:
                copied.set_cookie(copy.copy(cookie))
            replay = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(copied))
            _, csrf = call(client, 8083, "/api/csrf")
            expect(call(client, 8083, "/api/logout", b"", {csrf["headerName"]: csrf["token"]})[0] == 204, "Logout failed")
            expect(call(replay, 8082, "/api/v1/session")[0] == 401, "Old cookie still authorised after logout on peer")
            expect(call(replay, 8083, "/api/v1/session")[0] == 401, "Old cookie still authorised on logout instance")
            expired, _ = login(8082)
            sql("atlas", f"update {SCHEMA}.spring_session set last_access_time=0,expiry_time=0 where principal_name='sophia'")
            expect(call(expired, 8083, "/api/v1/session")[0] == 401, "Expired persisted session still authorised")
            report["checks"] = ["HttpOnly cookie", "shared authentication", "session survives restart", "logout on peer rejects copied cookie on both instances", "expired persisted session rejected"]
            report["status"] = "PASSED"
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        report["error"] = str(error) if isinstance(error, RuntimeError) else "Local session drill failed"
    finally:
        for process in processes:
            stop(process)
        report["totalSeconds"] = round(time.monotonic() - started, 3)
        Path("docs/evidence/session-cluster-check.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(run())
