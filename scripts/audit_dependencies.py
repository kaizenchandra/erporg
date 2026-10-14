"""Capture full and runtime npm audits; fail closed without discarding evidence."""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import subprocess


def audit(output, runner=subprocess.run):
    output.mkdir(parents=True, exist_ok=True)
    reports = {}
    errors = []
    for scope, flags in [("all", []), ("runtime", ["--omit=dev"])]:
        try:
            result = runner(["npm", "audit", "--json", *flags], capture_output=True,
                            text=True, timeout=120, check=False)
            report = json.loads(result.stdout)
            counts = report.get("metadata", {}).get("vulnerabilities", {})
            if result.returncode not in (0, 1) or report.get("error") or not all(
                isinstance(counts.get(key), int) and counts[key] >= 0
                for key in ("info", "low", "moderate", "high", "critical", "total")
            ):
                raise ValueError("Audit returned an invalid or unavailable report")
            (output / f"npm-{scope}.json").write_text(json.dumps(report, indent=2) + "\n")
            reports[scope] = counts
        except (ValueError, OSError, subprocess.TimeoutExpired):
            errors.append(scope)
            # Do not allow a previous report to masquerade as this run's evidence.
            (output / f"npm-{scope}.json").unlink(missing_ok=True)
    blocked = any(c["high"] or c["critical"] for c in reports.values())
    code = 2 if errors else 1 if blocked else 0
    summary = {
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "lockSha256": hashlib.sha256(Path("package-lock.json").read_bytes()).hexdigest(),
        "reports": reports,
        "unavailableScopes": errors,
        "gate": "ERROR" if errors else "BLOCKED" if blocked else "PASSED",
        "scope": "npm dependency advisories only; not a production release decision",
    }
    (output / "dependency-gate.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary))
    return code


if __name__ == "__main__":
    raise SystemExit(audit(Path("docs/evidence/dependencies")))
