"""Repeat the isolated local synthetic drill; existing evidence/databases are never replaced."""
import argparse
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4
import restore_check
import restore_application_check


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", type=Path, help="New evidence path prefix; both output files must not exist")
    options = parser.parse_args()
    prefix = options.prefix or Path("docs/evidence/timekeeping-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8])
    restore = Path(str(prefix) + "-restore.json")
    api = Path(str(prefix) + "-restored-api.json")
    if restore.exists() or api.exists():
        parser.error("Existing evidence will not be replaced; choose another prefix")
    if restore_check.run(restore) != 0:
        return 1
    names = ["legal_name", "additional_contacts", "contact_inbox", "benefit_withdrawal", "postal_address", "legal_entities", "reference_retirement", "retirement_notifications", "retirement_inbox", "birth_date", "company_calendars", "calendar_amendments", "calendar_retirement", "personal_email", "employee_calendar", "calendar_history", "entity_assignment", "audit_pagination", "department_directory", "reference_history", "benefit_history", "benefit_catalogue", "benefit_inbox", "department_parents", "timekeeping"]
    return restore_application_check.run(restore, api, **{"verify_" + name: True for name in names})


if __name__ == "__main__":
    raise SystemExit(main())
