"""Local synthetic restore drill. Never targets or overwrites an existing database."""
import hashlib
import json
import re
from pathlib import Path
import subprocess
import time
from datetime import datetime, timezone
from uuid import uuid4

BASE = ["docker", "compose", "exec", "-T", "postgres"]
SCHEMA = "atlas_acceptance"  # Synthetic browser fixtures only; never select a live schema.
TABLES = ["flyway_schema_history", "hr_employee", "identity_membership", "hr_request", "hr_version", "hr_audit", "hr_notification", "hr_department", "hr_department_audit", "hr_hire_request", "hr_hire_audit", "hr_employee_event", "hr_onboarding_completion", "hr_position", "hr_position_audit", "hr_position_assignment", "hr_reporting_assignment", "hr_request_withdrawal", "benefit_plan", "benefit_plan_audit", "benefit_selection", "benefit_dependant", "benefit_selection_audit", "benefit_deduction_handoff", "employee_document", "employee_document_audit", "employee_document_scan_retry", "employee_document_version", "document_template", "document_template_evidence", "document_template_revision", "document_template_reclassification", "document_retention_event", "hr_resignation", "hr_resignation_audit", "hr_resignation_handoff", "hr_exit_task", "hr_exit_task_audit", "hr_exit_task_management", "hr_employment_end", "hr_employment_end_handoff", "hr_end_withdrawal", "hr_end_withdrawal_decision", "hr_end_reschedule", "hr_end_reschedule_decision", "hr_end_reschedule_handoff", "hr_rehire", "hr_rehire_decision", "hr_employment_episode", "hr_rehire_handoff", "hr_rehire_activation", "hr_rehire_activation_decision", "hr_rehire_completion", "hr_rehire_access", "hr_rehire_access_revocation", "hr_local_access_grant", "hr_identity_replacement", "hr_identity_replacement_decision", "hr_resignation_withdrawal", "hr_case_episode", "hr_access_grant_episode", "hr_contact_request", "hr_contact_item", "hr_contact_audit", "hr_legal_entity", "hr_legal_entity_retirement_request", "hr_legal_entity_retirement_decision", "hr_work_calendar", "hr_work_calendar_revision", "hr_work_calendar_retirement", "hr_calendar_request_policy", "hr_calendar_assignment", "hr_legal_entity_assignment", "hr_entity_request_origin", "hr_department_parent_change"]
TABLES += ["time_shift", "time_sheet", "time_costing_entry", "time_command", "time_audit", "time_period", "time_batch_command"]


def command(args, payload=None):
    result = subprocess.run(BASE + args, input=payload, capture_output=True, timeout=120)
    if result.returncode:
        # Do not log stdout/stderr: database failures can contain personal data.
        raise RuntimeError(f"{args[0]} failed with exit {result.returncode}; inspect the local database privately")
    return result.stdout


def sql(database, statement, quiet=False):
    return command(["psql", "-X", *(["-q"] if quiet else []), "-U", "atlas", "-d", database, "-v", "ON_ERROR_STOP=1", "-Atc", statement]).decode().strip()


def _batch_manifest(database, names):
    # One read-only snapshot avoids CLI connection overhead and inconsistent per-table reads.
    # Only owned identifiers are interpolated; record content remains private inside this process.
    if not all(re.fullmatch(r"[a-z_][a-z0-9_]*", name) for name in [SCHEMA, *names]):
        raise ValueError("Invalid verification inventory identifier")
    statements = ["begin isolation level repeatable read read only;"]
    for name in names:
        statements.append(
            "select json_build_object('name','" + name + "','rows',coalesce(json_agg(payload order by payload collate \"C\"),'[]'::json)) "
            + 'from (select row_to_json(t)::text payload from "' + SCHEMA + '"."' + name + '" t) rows;'
        )
    statements.append("commit;")
    result = {}
    for line in sql(database, "\n".join(statements), quiet=True).splitlines():
        item = json.loads(line)
        rows = item["rows"]
        result[item["name"]] = {"rows": len(rows), "sha256": hashlib.sha256("\n".join(rows).encode()).hexdigest()}
    if set(result) != set(names):
        raise RuntimeError("Incomplete verification inventory")
    return result


def manifest(database):
    return _batch_manifest(database, TABLES)


def projection_manifest(database):
    return _batch_manifest(database, ["hr_exit_task_current", "hr_end_withdrawal_current", "hr_employment_end_current", "hr_employment_end_handoff_current", "hr_end_reschedule_current", "hr_rehire_current", "hr_rehire_activation_current", "hr_rehire_handoff_current", "hr_local_access_current", "hr_identity_replacement_current", "hr_episode_end_current", "hr_employment_episode_state", "hr_access_grant_episode_source", "hr_episode_access_current", "hr_legal_entity_retirement_current"])


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
        projection_before = projection_manifest("atlas")
        report["projectionManifest"] = projection_manifest(target)
        if report["projectionManifest"] != projection_before:
            raise RuntimeError("Restored current task/withdrawal/end/handoff projections differ")
        from document_objects import copy_objects
        restore_root = Path(__file__).resolve().parent.parent / "backend" / ".local" / "restored-documents"
        restore_root.mkdir(mode=0o700, parents=True, exist_ok=True)
        if restore_root.is_symlink() or restore_root.resolve() != restore_root or restore_root.stat().st_mode & 0o077:
            raise RuntimeError("Canonical private restore directory required")
        report["privateObjects"] = copy_objects(sql, target, restore_root / target.removeprefix("atlas_restore_"))
        checks = {
            "invalidApprovedTimeReconciliation": "select count(*) from atlas_acceptance.time_sheet s where(s.status='APPROVED' and (select coalesce(sum(minutes),0) from atlas_acceptance.time_costing_entry c where c.tenant=s.tenant and c.sheet_id=s.id)<>(s.snapshot->>'totalMinutes')::int) or(s.status<>'APPROVED' and exists(select 1 from atlas_acceptance.time_costing_entry c where c.tenant=s.tenant and c.sheet_id=s.id))",
            "invalidTimeAllocations": "select count(*) from atlas_acceptance.time_costing_entry c where not exists(select 1 from atlas_acceptance.time_sheet s cross join lateral jsonb_array_elements(s.snapshot->'entries') e where s.tenant=c.tenant and s.id=c.sheet_id and s.status='APPROVED' and(e->>'date')::date=c.work_date and(e->>'minutes')::integer=c.minutes and e->>'code'=c.activity_code)",
            "invalidAttendanceIntervals": "select count(*) from atlas_acceptance.time_shift a join atlas_acceptance.time_shift b on a.tenant=b.tenant and a.employee_id=b.employee_id and a.episode_start=b.episode_start and a.id<b.id where tstzrange(a.started_at,a.ended_at,'[)')&&tstzrange(b.started_at,b.ended_at,'[)')",

            "invalidDepartmentParentSequence": "select count(*) from atlas_acceptance.hr_department_parent_change c join atlas_acceptance.hr_department d on d.tenant=c.tenant and d.code=c.code left join atlas_acceptance.hr_department_parent_change p on p.tenant=c.tenant and p.code=c.code and p.version=c.version-1 where(c.version>1 and p.code is null) or c.old_parent_code is distinct from(case when p.code is null then d.parent_code else p.parent_code end) or c.effective_date<coalesce(p.effective_date,d.effective_from) or c.effective_date<>(c.recorded_at at time zone 'UTC')::date",

            "invalidBenefitWithdrawal": "select count(*) from atlas_acceptance.benefit_selection s where s.status='WITHDRAWN' and (s.decided_by<>s.requested_by or s.version<>1 or exists(select 1 from atlas_acceptance.benefit_deduction_handoff h where h.tenant=s.tenant and h.selection_id=s.id) or (select count(*) from atlas_acceptance.benefit_selection_audit a where a.tenant=s.tenant and a.selection_id=s.id and a.action='WITHDRAWN' and a.actor=s.requested_by and a.reason=s.decision_reason and a.recorded_at>=s.recorded_at and length(a.correlation_id)>0)<>1)",

            "invalidContactSnapshots": "select count(*) from atlas_acceptance.hr_contact_request r where (select count(*) from atlas_acceptance.hr_contact_item i where i.tenant=r.tenant and i.request_id=r.id and side='OLD')<>r.old_count or (select coalesce(max(ordinal),0) from atlas_acceptance.hr_contact_item i where i.tenant=r.tenant and i.request_id=r.id and side='OLD')<>r.old_count or (select count(*) from atlas_acceptance.hr_contact_item i where i.tenant=r.tenant and i.request_id=r.id and side='NEW')<>r.new_count or (select coalesce(max(ordinal),0) from atlas_acceptance.hr_contact_item i where i.tenant=r.tenant and i.request_id=r.id and side='NEW')<>r.new_count",
            "invalidContactAudit": "select count(*) from atlas_acceptance.hr_contact_request r where (select count(*) from atlas_acceptance.hr_contact_audit a where a.tenant=r.tenant and a.request_id=r.id)<>(case when r.status='SUBMITTED' then 1 else 2 end) or not exists(select 1 from atlas_acceptance.hr_contact_audit a where a.tenant=r.tenant and a.request_id=r.id and a.status='SUBMITTED' and a.actor=r.requested_by and a.reason=r.reason and a.effective_date=r.effective_date and a.recorded_at=r.recorded_at and a.correlation_id=r.correlation_id) or(r.status<>'SUBMITTED' and not exists(select 1 from atlas_acceptance.hr_contact_audit a where a.tenant=r.tenant and a.request_id=r.id and a.status=r.status and a.actor=r.decided_by and a.reason=r.comment and a.effective_date=r.effective_date and a.recorded_at=r.decided_at and a.correlation_id=r.decision_correlation_id))",

            "invalidRetentionOwnerSequence": "select count(*) from atlas_acceptance.document_retention_event r left join atlas_acceptance.employee_document d on d.tenant=r.tenant and d.id=r.root_id where d.id is null or d.employee_id<>r.employee_id or exists(select 1 from atlas_acceptance.employee_document_version v where v.tenant=r.tenant and v.document_id=r.root_id) or (r.version>1 and not exists(select 1 from atlas_acceptance.document_retention_event p where p.tenant=r.tenant and p.root_id=r.root_id and p.version=r.version-1))",
            "invalidHoldRelease": "select count(*) from atlas_acceptance.document_retention_event r left join atlas_acceptance.document_retention_event p on p.tenant=r.tenant and p.root_id=r.root_id and p.action='PLACE_HOLD' and p.hold_id=r.hold_id and p.version<r.version where r.action='RELEASE_HOLD' and (p.hold_id is null or p.actor=r.actor or p.actor_employee_id=r.actor_employee_id)",
            "shortenedRetention": "select count(*) from atlas_acceptance.document_retention_event r where r.action='SET_POLICY' and r.retain_until<(select max(p.retain_until) from atlas_acceptance.document_retention_event p where p.tenant=r.tenant and p.root_id=r.root_id and p.action='SET_POLICY' and p.version<r.version)",
            "invalidTemplateRevisionSequence": "select count(*) from atlas_acceptance.document_template_revision r left join atlas_acceptance.document_template t on t.tenant=r.tenant and t.code=r.code left join atlas_acceptance.document_template_revision p on p.tenant=r.tenant and p.code=r.code and p.version=r.version-1 where t.code is null or (r.version>2 and p.version is null) or coalesce(p.retired,false) or r.effective_date<coalesce(p.effective_date,t.effective_date)",
            "invalidClassificationCorrection": "select count(*) from atlas_acceptance.document_template_reclassification r left join atlas_acceptance.document_template_evidence e on e.tenant=r.tenant and e.code=r.code and e.employee_id=r.employee_id left join atlas_acceptance.document_template_reclassification p on p.tenant=r.tenant and p.code=r.code and p.employee_id=r.employee_id and p.classification_version=r.classification_version-1 left join atlas_acceptance.employee_document d on d.tenant=r.tenant and d.id=r.root_id where e.code is null or d.id is null or d.employee_id<>r.employee_id or (r.classification_version>2 and p.classification_version is null) or r.previous_root_id<>coalesce(p.root_id,e.root_id) or exists(select 1 from atlas_acceptance.employee_document_version v where v.tenant=r.tenant and v.document_id=r.root_id)",
            "reusedInitialClassificationRoot": "select count(*) from atlas_acceptance.document_template_reclassification r join atlas_acceptance.document_template_evidence e on e.tenant=r.tenant and e.root_id=r.root_id",
            "invalidTemplateEvidence": "select count(*) from atlas_acceptance.document_template_evidence e left join atlas_acceptance.document_template t on t.tenant=e.tenant and t.code=e.code left join atlas_acceptance.employee_document d on d.tenant=e.tenant and d.id=e.root_id where t.code is null or d.id is null or d.employee_id<>e.employee_id or exists(select 1 from atlas_acceptance.employee_document_version v where v.tenant=e.tenant and v.document_id=e.root_id) or e.linked_version>coalesce((select max(v.version) from atlas_acceptance.employee_document_version v where v.tenant=e.tenant and v.root_id=e.root_id),1)",
            "orphanRequests": "select count(*) from atlas_acceptance.hr_request r left join atlas_acceptance.hr_employee e on e.tenant=r.tenant and e.id=r.employee_id where e.id is null",
            "orphanVersions": "select count(*) from atlas_acceptance.hr_version v left join atlas_acceptance.hr_request r on r.id=v.request_id and r.tenant=v.tenant where r.id is null",
            "orphanAudit": "select count(*) from atlas_acceptance.hr_audit a left join atlas_acceptance.hr_request r on r.id=a.request_id and r.tenant=a.tenant where r.id is null",
            "orphanDepartments": "select count(*) from atlas_acceptance.hr_department d left join atlas_acceptance.hr_department p on p.tenant=d.tenant and p.code=d.parent_code where d.parent_code is not null and p.code is null",
            "orphanDepartmentAudit": "select count(*) from atlas_acceptance.hr_department_audit a left join atlas_acceptance.hr_department d on d.tenant=a.tenant and d.code=a.code where d.code is null",
            "invalidRehireDecision": "select count(*) from atlas_acceptance.hr_rehire_current r where r.actor_employee_id=r.employee_id or r.reviewer=r.actor or r.reviewer_employee_id in(r.actor_employee_id,r.employee_id) or r.start_date<r.prior_inactive_date or (r.status='APPROVED' and (r.review_version<>r.input_version or r.start_date<(r.reviewed_at at time zone 'UTC')::date))",
            "invalidRehireEpisodeHandoffs": "select count(*) from atlas_acceptance.hr_rehire_current r where (r.status='APPROVED' and (not exists(select 1 from atlas_acceptance.hr_employment_episode e where e.tenant=r.tenant and e.rehire_id=r.id and e.employee_id=r.employee_id and e.number=2 and e.start_date=r.start_date and e.prior_inactive_date=r.prior_inactive_date and e.recorded_at=r.reviewed_at and e.correlation_id=r.review_correlation_id) or (select count(*) from atlas_acceptance.hr_rehire_handoff h where h.tenant=r.tenant and h.rehire_id=r.id and h.start_date=r.start_date and h.recorded_at=r.reviewed_at and h.correlation_id=r.review_correlation_id)<>3)) or (r.status<>'APPROVED' and (exists(select 1 from atlas_acceptance.hr_employment_episode e where e.tenant=r.tenant and e.rehire_id=r.id) or exists(select 1 from atlas_acceptance.hr_rehire_handoff h where h.tenant=r.tenant and h.rehire_id=r.id)))",
            "invalidEndReschedule": "select count(*) from atlas_acceptance.hr_end_reschedule_current r join atlas_acceptance.hr_employment_end e on e.tenant=r.tenant and e.request_id=r.request_id where r.employee_id<>e.employee_id or r.actor_employee_id=r.employee_id or r.reviewer=r.actor or r.reviewer_employee_id in(r.actor_employee_id,r.employee_id) or (r.status='APPROVED' and (r.review_version<>r.input_version or r.last_working_date<(r.reviewed_at at time zone 'UTC')::date or r.prior_inactive_date<=(r.reviewed_at at time zone 'UTC')::date))",
            "invalidEndRescheduleHandoffs": "select count(*) from atlas_acceptance.hr_end_reschedule_current r where (r.status='APPROVED' and (select count(*) from atlas_acceptance.hr_end_reschedule_handoff h where h.tenant=r.tenant and h.reschedule_id=r.id and h.inactive_date=r.last_working_date+1 and h.recorded_at=r.reviewed_at and h.correlation_id=r.review_correlation_id)<>2) or (r.status<>'APPROVED' and exists(select 1 from atlas_acceptance.hr_end_reschedule_handoff h where h.tenant=r.tenant and h.reschedule_id=r.id))",
            "invalidEndWithdrawalDecision": "select count(*) from atlas_acceptance.hr_end_withdrawal_current w join atlas_acceptance.hr_employment_end e on e.tenant=w.tenant and e.request_id=w.request_id where w.employee_id<>e.employee_id or w.actor_employee_id=w.employee_id or w.reviewer=w.actor or w.reviewer_employee_id in(w.actor_employee_id,w.employee_id) or (w.status='APPROVED' and ((w.reviewed_at at time zone 'UTC')::date>=e.inactive_date or w.review_version<>w.input_version))",
            "invalidEmploymentEnd": "select count(*) from atlas_acceptance.hr_employment_end e left join atlas_acceptance.hr_resignation r on r.tenant=e.tenant and r.id=e.request_id and r.employee_id=e.employee_id and r.status='APPROVED' and r.last_working_date=e.last_working_date where r.id is null or e.inactive_date<>e.last_working_date+1 or e.new_version<>e.input_version+1 or e.actor_employee_id=e.employee_id",
            "invalidEmploymentEndHandoffs": "select count(*) from atlas_acceptance.hr_employment_end e where (select count(*) from atlas_acceptance.hr_employment_end_handoff h where h.tenant=e.tenant and h.employee_id=e.employee_id and h.inactive_date=e.inactive_date and h.recorded_at=e.recorded_at and h.correlation_id=e.correlation_id and h.status='PENDING')<>2",
            "invalidExitTaskManagementSequence": "select count(*) from atlas_acceptance.hr_exit_task_management m join atlas_acceptance.hr_exit_task t on t.tenant=m.tenant and t.id=m.task_id left join atlas_acceptance.hr_exit_task_management p on p.tenant=m.tenant and p.task_id=m.task_id and p.ordinal=m.ordinal-1 where (m.ordinal>1 and p.ordinal is null) or coalesce(p.new_assignee_id,t.assignee_id)<>m.old_assignee_id or coalesce(p.new_status,'OPEN')<>'OPEN' or (t.completed_at is not null and m.recorded_at>t.completed_at)",
            "invalidExitTaskManagementTerminal": "select count(*) from atlas_acceptance.hr_exit_task_management m join atlas_acceptance.hr_exit_task t on t.tenant=m.tenant and t.id=m.task_id where m.new_status='CANCELLED' and t.status='COMPLETED'",
            "invalidExitTaskCaseOwner": "select count(*) from atlas_acceptance.hr_exit_task t left join atlas_acceptance.hr_resignation r on r.tenant=t.tenant and r.id=t.request_id and r.employee_id=t.employee_id and r.status='APPROVED' where r.id is null",
            "invalidExitTaskSequence": "select count(*) from atlas_acceptance.hr_exit_task t where t.ordinal<>(select count(*) from atlas_acceptance.hr_exit_task p where p.tenant=t.tenant and p.request_id=t.request_id and p.ordinal<=t.ordinal)",
            "invalidExitTaskAudit": "select count(*) from atlas_acceptance.hr_exit_task t where (select count(*) from atlas_acceptance.hr_exit_task_audit a where a.tenant=t.tenant and a.task_id=t.id)<>(case when t.status='OPEN' then 1 else 2 end) or not exists(select 1 from atlas_acceptance.hr_exit_task_audit a where a.tenant=t.tenant and a.task_id=t.id and a.new_status=t.status and a.actor=case when t.status='OPEN' then t.created_by else t.completed_by end and a.reason=case when t.status='OPEN' then t.reason else t.completion_comment end and a.effective_date=t.due_date)",
            "orphanResignations": "select count(*) from atlas_acceptance.hr_resignation r left join atlas_acceptance.hr_employee e on e.tenant=r.tenant and e.id=r.employee_id where e.id is null",
            "inconsistentResignationHandoffs": "select count(*) from atlas_acceptance.hr_resignation_handoff h left join atlas_acceptance.hr_resignation r on r.tenant=h.tenant and r.id=h.request_id and r.employee_id=h.employee_id and r.last_working_date=h.effective_date and r.status='APPROVED' where r.id is null",
            "missingResignationHandoffs": "select count(*) from atlas_acceptance.hr_resignation r where r.status='APPROVED' and not exists(select 1 from atlas_acceptance.hr_resignation_handoff h where h.tenant=r.tenant and h.request_id=r.id)",
            "inconsistentResignationAudit": "select count(*) from atlas_acceptance.hr_resignation r where (select count(*) from atlas_acceptance.hr_resignation_audit a where a.tenant=r.tenant and a.request_id=r.id)<>case when r.status='SUBMITTED' then 1 else 2 end or not exists(select 1 from atlas_acceptance.hr_resignation_audit a where a.tenant=r.tenant and a.request_id=r.id and a.new_status=r.status and a.reason=case when r.status='SUBMITTED' then r.reason when r.status='WITHDRAWN' then (select w.reason from atlas_acceptance.hr_resignation_withdrawal w where w.tenant=r.tenant and w.request_id=r.id) else r.comment end)",
            "orphanHires": "select count(*) from atlas_acceptance.hr_hire_request h left join atlas_acceptance.hr_employee e on e.tenant=h.tenant and e.id=h.employee_id where h.status='APPROVED' and e.id is null",
            "orphanHireAudit": "select count(*) from atlas_acceptance.hr_hire_audit a left join atlas_acceptance.hr_hire_request h on h.id=a.request_id and h.tenant=a.tenant where h.id is null",
            "orphanEmployeeEvents": "select count(*) from atlas_acceptance.hr_employee_event v left join atlas_acceptance.hr_employee e on e.tenant=v.tenant and e.id=v.employee_id where e.id is null",
            "orphanBenefitAudit": "select count(*) from atlas_acceptance.benefit_plan_audit a left join atlas_acceptance.benefit_plan p on p.tenant=a.tenant and p.code=a.code where p.code is null",
            "inconsistentBenefitDeductions": "select count(*) from atlas_acceptance.benefit_deduction_handoff h left join atlas_acceptance.benefit_selection s on s.tenant=h.tenant and s.id=h.selection_id and s.status='APPROVED' and s.employee_id=h.employee_id and s.currency=h.currency and s.monthly_contribution=h.monthly_contribution and s.effective_date=h.effective_date and s.rule_version=h.rule_version where s.id is null",
            "missingBenefitDeductions": "select count(*) from atlas_acceptance.benefit_selection s left join atlas_acceptance.benefit_deduction_handoff h on h.tenant=s.tenant and h.selection_id=s.id where s.status='APPROVED' and h.selection_id is null",
            "orphanBenefitDependants": "select count(*) from atlas_acceptance.benefit_dependant d left join atlas_acceptance.benefit_selection s on s.tenant=d.tenant and s.id=d.selection_id where s.id is null",
            "orphanBenefitSelectionAudit": "select count(*) from atlas_acceptance.benefit_selection_audit a left join atlas_acceptance.benefit_selection s on s.tenant=a.tenant and s.id=a.selection_id where s.id is null",
            "invalidDocumentVersions": "select count(*) from atlas_acceptance.employee_document_version v left join atlas_acceptance.employee_document r on r.tenant=v.tenant and r.id=v.root_id left join atlas_acceptance.employee_document d on d.tenant=v.tenant and d.id=v.document_id and d.employee_id=r.employee_id and d.requested_by=v.actor and d.reason=v.reason and d.recorded_at=v.recorded_at where d.id is null",
            "invalidDocumentVersionSequence": "select count(*) from atlas_acceptance.employee_document_version v left join atlas_acceptance.employee_document_version p on p.tenant=v.tenant and p.root_id=v.root_id and p.document_id=v.parent_id and p.version=v.version-1 where (v.version=2 and v.parent_id<>v.root_id) or (v.version>2 and p.document_id is null)",
            "invalidDocumentRetry": "select count(*) from atlas_acceptance.employee_document_scan_retry r left join atlas_acceptance.employee_document d on d.tenant=r.tenant and d.id=r.document_id and d.status='QUARANTINED' and d.sha256=r.scanned_sha256 where d.id is null",
            "invalidDocumentRetrySequence": "select count(*) from atlas_acceptance.employee_document_scan_retry r left join atlas_acceptance.employee_document_scan_retry p on p.tenant=r.tenant and p.document_id=r.document_id and p.attempt=r.attempt-1 and p.status='QUARANTINED' where r.attempt>1 and p.attempt is null",
            "orphanDocumentAudit": "select count(*) from atlas_acceptance.employee_document_audit a left join atlas_acceptance.employee_document d on d.tenant=a.tenant and d.id=a.document_id where d.id is null",
            "missingDocumentAudit": "select count(*) from atlas_acceptance.employee_document d where not exists(select 1 from atlas_acceptance.employee_document_audit a where a.tenant=d.tenant and a.document_id=d.id and a.action='UPLOADED') or not exists(select 1 from atlas_acceptance.employee_document_audit a where a.tenant=d.tenant and a.document_id=d.id and a.action=d.status)",
            "inconsistentWithdrawals": "select count(*) from atlas_acceptance.hr_request_withdrawal w left join atlas_acceptance.hr_request r on r.tenant=w.tenant and r.id=w.request_id and r.status='WITHDRAWN' and r.requested_by=w.actor and r.decided_by=w.actor and r.comment=w.reason where r.id is null",
            "inconsistentReportingAssignments": "select count(*) from atlas_acceptance.hr_reporting_assignment a left join atlas_acceptance.hr_version v on v.tenant=a.tenant and v.employee_id=a.employee_id and v.request_id=a.request_id and v.field='managerId' and v.value=a.manager_id and v.effective_date=a.effective_date and v.recorded_at=a.recorded_at where v.id is null",
            "missingReportingAssignments": "select count(*) from atlas_acceptance.hr_version v left join atlas_acceptance.hr_reporting_assignment a on a.tenant=v.tenant and a.request_id=v.request_id where v.field='managerId' and a.request_id is null",
            "inconsistentPositionAssignments": "select count(*) from atlas_acceptance.hr_position_assignment a left join atlas_acceptance.hr_version v on v.tenant=a.tenant and v.employee_id=a.employee_id and v.request_id=a.request_id and v.field='positionCode' and v.value=coalesce(a.position_code,'') and v.effective_date=a.effective_date and v.recorded_at=a.recorded_at where v.id is null",
            "missingPositionAssignments": "select count(*) from atlas_acceptance.hr_version v left join atlas_acceptance.hr_position_assignment a on a.tenant=v.tenant and a.request_id=v.request_id where v.field='positionCode' and a.request_id is null",
            "orphanPositions": "select count(*) from atlas_acceptance.hr_position p left join atlas_acceptance.hr_department d on d.tenant=p.tenant and d.code=p.department_code where d.code is null",
            "orphanPositionAudit": "select count(*) from atlas_acceptance.hr_position_audit a left join atlas_acceptance.hr_position p on p.tenant=a.tenant and p.code=a.code where p.code is null",
            "orphanCompletions": "select count(*) from atlas_acceptance.hr_onboarding_completion c left join atlas_acceptance.hr_hire_request h on h.tenant=c.tenant and h.id=c.hire_id and h.employee_id=c.employee_id where h.id is null",
            "failedMigrations": "select count(*) from atlas_acceptance.flyway_schema_history where not success",
        }
        checks["missingCaseEpisode"] = "select count(*) from atlas_acceptance.hr_resignation r left join atlas_acceptance.hr_case_episode a on a.tenant=r.tenant and a.request_id=r.id and a.employee_id=r.employee_id and a.source_recorded_at=r.recorded_at where a.request_id is null"
        checks["invalidCaseEpisode"] = "select count(*) from atlas_acceptance.hr_case_episode a join atlas_acceptance.hr_resignation r on r.tenant=a.tenant and r.id=a.request_id where a.episode_number<>1 or (a.origin in('COMMAND','COMPAT_COMMAND') and (a.linked_at<>a.source_recorded_at or a.linked_by<>r.requested_by or not exists(select 1 from atlas_acceptance.hr_resignation_audit v where v.tenant=a.tenant and v.request_id=a.request_id and v.new_status='SUBMITTED' and v.recorded_at=a.source_recorded_at and v.actor=a.linked_by and v.correlation_id=a.correlation_id))) or (a.origin='MIGRATED_V42' and (a.linked_by<>'SYSTEM_MIGRATION_V43' or a.correlation_id<>'migration-v43'))"
        checks["missingEpisodeOwnedEnd"] = "select count(*) from atlas_acceptance.hr_employment_end_current e left join atlas_acceptance.hr_episode_end_current p on p.tenant=e.tenant and p.request_id=e.request_id and p.employee_id=e.employee_id and p.episode_number=1 and p.inactive_date=e.inactive_date where p.request_id is null"
        checks["missingResignationWithdrawal"] = "select count(*) from atlas_acceptance.hr_resignation r where (r.status='WITHDRAWN') <> exists(select 1 from atlas_acceptance.hr_resignation_withdrawal w where w.tenant=r.tenant and w.request_id=r.id)"
        checks["invalidResignationWithdrawal"] = "select count(*) from atlas_acceptance.hr_resignation_withdrawal w left join atlas_acceptance.hr_resignation r on r.tenant=w.tenant and r.id=w.request_id left join atlas_acceptance.hr_resignation_audit a on a.tenant=w.tenant and a.request_id=w.request_id and a.new_status='WITHDRAWN' and a.actor=w.actor and a.reason=w.reason and a.recorded_at=w.recorded_at and a.correlation_id=w.correlation_id and a.effective_date=r.last_working_date where r.id is null or r.status<>'WITHDRAWN' or r.employee_id<>w.employee_id or r.requested_by<>w.actor or w.input_version<>0 or w.new_version<>1 or r.version<>w.new_version or w.recorded_at<r.recorded_at or a.id is null"
        checks["invalidRehireCompletionGrant"] = "select count(*) from atlas_acceptance.hr_rehire_completion c left join atlas_acceptance.hr_rehire_activation_current a on a.tenant=c.tenant and a.id=c.activation_id left join atlas_acceptance.hr_rehire_access g on g.tenant=c.tenant and g.activation_id=c.activation_id where a.id is null or a.status<>'APPROVED' or a.subject<>c.subject or a.employee_id<>c.employee_id or a.reviewer<>c.actor or a.reviewed_at<>c.recorded_at or a.review_correlation_id<>c.correlation_id or a.review_version<>c.input_version or g.activation_id is null or g.subject<>c.subject or g.employee_id<>c.employee_id or g.role<>'EMPLOYEE' or c.role<>'EMPLOYEE' or g.effective_date<>c.effective_date or g.recorded_at<>c.recorded_at or g.correlation_id<>c.correlation_id"
        checks["missingRehireCompletion"] = "select count(*) from atlas_acceptance.hr_rehire_activation_current a where (a.status='APPROVED') <> exists(select 1 from atlas_acceptance.hr_rehire_completion c where c.tenant=a.tenant and c.activation_id=a.id)"
        checks["invalidRehireActivationSeparation"] = "select count(*) from atlas_acceptance.hr_rehire_activation_current a where a.actor_employee_id=a.employee_id or a.reviewer=a.actor or a.reviewer_employee_id in(a.actor_employee_id,a.employee_id)"
        checks["invalidRehireAccessRevocation"] = "select count(*) from atlas_acceptance.hr_rehire_access_revocation r left join atlas_acceptance.hr_local_access_grant g on g.tenant=r.tenant and g.id=r.activation_id where g.id is null or g.employee_id<>r.employee_id or g.subject<>r.subject or r.actor_employee_id=r.employee_id or r.new_version<>r.input_version+1 or r.recorded_at<g.recorded_at"
        checks["missingInitialRegistryCopy"] = "select count(*) from atlas_acceptance.hr_rehire_access a left join atlas_acceptance.hr_local_access_grant g on g.tenant=a.tenant and g.id=a.activation_id and g.employee_id=a.employee_id and g.subject=a.subject and g.role=a.role and g.epoch=a.epoch and g.effective_date=a.effective_date and g.recorded_at=a.recorded_at and g.correlation_id=a.correlation_id and g.ordinal=1 where g.id is null"
        checks["invalidReplacementRegistry"] = "select count(*) from atlas_acceptance.hr_local_access_grant g left join atlas_acceptance.hr_identity_replacement_current r on r.tenant=g.tenant and r.id=g.id left join atlas_acceptance.hr_local_access_grant p on p.tenant=g.tenant and p.id=g.predecessor_id left join atlas_acceptance.hr_rehire_access_revocation x on x.tenant=p.tenant and x.activation_id=p.id where g.ordinal=2 and (r.id is null or r.status<>'APPROVED' or r.subject<>g.subject or r.employee_id<>g.employee_id or r.predecessor_id<>g.predecessor_id or r.reviewed_at<>g.recorded_at or r.review_correlation_id<>g.correlation_id or r.input_version<>r.review_version or p.employee_id<>g.employee_id or p.ordinal<>1 or p.subject=g.subject or x.activation_id is null or g.role<>'EMPLOYEE')"
        checks["missingReplacementGrant"] = "select count(*) from atlas_acceptance.hr_identity_replacement_current r where (r.status='APPROVED') <> exists(select 1 from atlas_acceptance.hr_local_access_grant g where g.tenant=r.tenant and g.id=r.id and g.ordinal=2)"
        checks["invalidReplacementSeparation"] = "select count(*) from atlas_acceptance.hr_identity_replacement_current r where r.actor_employee_id=r.employee_id or r.reviewer=r.actor or r.reviewer_employee_id in(r.actor_employee_id,r.employee_id)"
        checks["missingGrantEpisode"] = "select count(*) from atlas_acceptance.hr_local_access_grant g left join atlas_acceptance.hr_access_grant_episode p on p.tenant=g.tenant and p.grant_id=g.id where p.grant_id is null"
        checks["invalidGrantEpisode"] = "select count(*) from atlas_acceptance.hr_access_grant_episode p left join atlas_acceptance.hr_access_grant_episode_source s on s.tenant=p.tenant and s.grant_id=p.grant_id and s.employee_id=p.employee_id and s.episode_number=p.episode_number and s.rehire_id=p.rehire_id and s.episode_start_date=p.episode_start_date and s.grant_sequence=p.grant_sequence where s.grant_id is null"
        checks["invalidPostalRequest"] = "select count(*) from atlas_acceptance.hr_request where field='address' and (not atlas_acceptance.hr_postal_value_valid(old_value) or not atlas_acceptance.hr_postal_value_valid(new_value))"
        checks["invalidPostalVersion"] = "select count(*) from atlas_acceptance.hr_version where field='address' and not atlas_acceptance.hr_postal_value_valid(value)"
        checks["invalidPostalAudit"] = "select count(*) from atlas_acceptance.hr_audit where field='address' and (not atlas_acceptance.hr_postal_value_valid(old_value) or not atlas_acceptance.hr_postal_value_valid(new_value))"
        checks["legalEntityCatalogueOverLimit"] = "select count(*) from (select tenant from atlas_acceptance.hr_legal_entity group by tenant having count(*)>200) t"
        checks["retirementIndependenceChronology"] = "select count(*) from atlas_acceptance.hr_legal_entity_retirement_decision d join atlas_acceptance.hr_legal_entity_retirement_request r on r.tenant=d.tenant and r.id=d.request_id where d.actor=r.requested_by or d.actor_employee_id=r.requester_employee_id or d.recorded_at<r.recorded_at or (d.decision='APPROVED' and r.effective_date<(d.recorded_at at time zone 'UTC')::date)"
        checks["retirementAttemptOrder"] = "select count(*) from atlas_acceptance.hr_legal_entity_retirement_request r left join atlas_acceptance.hr_legal_entity_retirement_request p on p.tenant=r.tenant and p.code=r.code and p.ordinal=r.ordinal-1 left join atlas_acceptance.hr_legal_entity_retirement_decision d on d.tenant=p.tenant and d.request_id=p.id where r.ordinal>1 and (p.id is null or d.decision is distinct from 'REJECTED' or r.recorded_at<d.recorded_at)"
        checks["missingEntityOrigins"] = "select count(*) from atlas_acceptance.hr_request r left join atlas_acceptance.hr_entity_request_origin o on o.tenant=r.tenant and o.request_id=r.id and o.recorded_at=r.recorded_at where r.field='legalEntityCode' and o.request_id is null"
        checks["inconsistentEntityLedger"] = "select count(*) from atlas_acceptance.hr_legal_entity_assignment a left join atlas_acceptance.hr_version v on v.tenant=a.tenant and v.id=a.version_id and v.request_id=a.request_id and v.employee_id=a.employee_id and v.field='legalEntityCode' and nullif(v.value,'') is not distinct from a.entity_code and v.effective_date=a.effective_date and v.recorded_at=a.recorded_at left join atlas_acceptance.hr_request r on r.tenant=a.tenant and r.id=a.request_id and r.status='APPROVED' where v.id is null or r.id is null"
        checks["missingEntityLedger"] = "select count(*) from atlas_acceptance.hr_version v left join atlas_acceptance.hr_legal_entity_assignment a on a.tenant=v.tenant and a.version_id=v.id where v.field='legalEntityCode' and a.version_id is null"
        checks["wrongEntityEpisode"] = "set search_path=atlas_acceptance,pg_catalog; select count(*) from atlas_acceptance.hr_legal_entity_assignment a join atlas_acceptance.hr_employee e on e.tenant=a.tenant and e.id=a.employee_id where a.episode_start<>atlas_acceptance.hr_employment_start(e.tenant,e.id,e.start_date,a.effective_date)"
        for table in ["hr_request", "hr_version", "hr_audit"]:
            predicate = "not atlas_acceptance.hr_birth_date_valid(r.value,(r.recorded_at at time zone 'UTC')::date,e.start_date,false)" if table == "hr_version" else "not atlas_acceptance.hr_birth_date_valid(r.old_value,(r.recorded_at at time zone 'UTC')::date,e.start_date,true) or not atlas_acceptance.hr_birth_date_valid(r.new_value,(r.recorded_at at time zone 'UTC')::date,e.start_date,false)"
            checks["invalidBirthDate_" + table] = "select count(*) from atlas_acceptance." + table + " r join atlas_acceptance.hr_employee e on e.tenant=r.tenant and e.id=r.employee_id where r.field='birthDate' and (" + predicate + ")"
        checks["invalidCompanyCalendar"] = "select count(*) from atlas_acceptance.hr_work_calendar where not atlas_acceptance.hr_calendar_days_valid(working_days) or not atlas_acceptance.hr_calendar_closures_valid(closed_dates,effective_from,effective_until) or effective_until<effective_from or effective_until>=effective_from+interval '1 year'"
        checks["companyCalendarCatalogueOverLimit"] = "select count(*) from (select tenant from atlas_acceptance.hr_work_calendar group by tenant having count(*)>200) c"
        for table in ["hr_request", "hr_version", "hr_audit"]:
            predicate = "not atlas_acceptance.hr_personal_email_valid(value)" if table == "hr_version" else "not atlas_acceptance.hr_personal_email_valid(old_value) or not atlas_acceptance.hr_personal_email_valid(new_value)"
            checks["invalidPersonalEmail_" + table] = "select count(*) from atlas_acceptance." + table + " where field='personalEmail' and (" + predicate + ")"
        checks["invalidCalendarRequestPolicy"] = "select count(*) from atlas_acceptance.hr_calendar_request_policy p left join atlas_acceptance.hr_request r on r.tenant=p.tenant and r.id=p.request_id where r.id is null or r.field<>'calendarCode' or nullif(r.new_value,'') is distinct from p.calendar_code or r.recorded_at<>p.recorded_at or(p.calendar_code is not null and p.policy_version>(select count(*) from atlas_acceptance.hr_work_calendar_revision v where v.tenant=p.tenant and v.code=p.calendar_code))"
        checks["missingCalendarRequestPolicy"] = "select count(*) from atlas_acceptance.hr_request r left join atlas_acceptance.hr_calendar_request_policy p on p.tenant=r.tenant and p.request_id=r.id where r.field='calendarCode' and p.request_id is null"
        checks["invalidCalendarAssignment"] = "select count(*) from atlas_acceptance.hr_calendar_assignment a left join atlas_acceptance.hr_version v on v.tenant=a.tenant and v.id=a.version_id left join atlas_acceptance.hr_request r on r.tenant=a.tenant and r.id=a.request_id left join atlas_acceptance.hr_calendar_request_policy p on p.tenant=a.tenant and p.request_id=a.request_id left join atlas_acceptance.hr_employee e on e.tenant=a.tenant and e.id=a.employee_id where v.id is null or r.id is null or p.request_id is null or e.id is null or v.field<>'calendarCode' or r.field<>'calendarCode' or r.status<>'APPROVED' or v.request_id<>a.request_id or v.employee_id<>a.employee_id or r.employee_id<>a.employee_id or nullif(v.value,'') is distinct from a.calendar_code or nullif(r.new_value,'') is distinct from a.calendar_code or v.effective_date<>a.effective_date or r.effective_date<>a.effective_date or v.recorded_at<>a.recorded_at or a.recorded_at<r.recorded_at or a.effective_date<a.episode_start or(a.episode_start<>e.start_date and not exists(select 1 from atlas_acceptance.hr_employment_episode ep where ep.tenant=a.tenant and ep.employee_id=a.employee_id and ep.start_date=a.episode_start)) or(a.calendar_code is not null and(p.policy_version<>(select count(*) from atlas_acceptance.hr_work_calendar_revision c where c.tenant=a.tenant and c.code=a.calendar_code) or exists(select 1 from atlas_acceptance.hr_work_calendar_retirement t where t.tenant=a.tenant and t.code=a.calendar_code)))"
        checks["missingCalendarAssignment"] = "select count(*) from atlas_acceptance.hr_version v left join atlas_acceptance.hr_calendar_assignment a on a.tenant=v.tenant and a.version_id=v.id where v.field='calendarCode' and a.request_id is null"
        checks["invalidCalendarRetirement"] = "select count(*) from atlas_acceptance.hr_work_calendar_retirement r left join atlas_acceptance.hr_work_calendar b on b.tenant=r.tenant and b.code=r.code where b.code is null or r.effective_date<b.effective_from or r.effective_date>b.effective_until or r.recorded_at<b.recorded_at or r.calendar_version<>(select count(*) from atlas_acceptance.hr_work_calendar_revision v where v.tenant=r.tenant and v.code=r.code) or exists(select 1 from atlas_acceptance.hr_work_calendar_revision v where v.tenant=r.tenant and v.code=r.code and(v.effective_date>=r.effective_date or v.recorded_at>r.recorded_at))"
        checks["invalidCalendarAmendment"] = "select count(*) from atlas_acceptance.hr_work_calendar_revision r left join atlas_acceptance.hr_work_calendar b on b.tenant=r.tenant and b.code=r.code left join atlas_acceptance.hr_work_calendar_revision p on p.tenant=r.tenant and p.code=r.code and p.ordinal=r.ordinal-1 where b.code is null or r.effective_date<b.effective_from or r.effective_date>b.effective_until or r.recorded_at<b.recorded_at or not atlas_acceptance.hr_calendar_days_valid(r.working_days) or not atlas_acceptance.hr_calendar_closures_valid(r.closed_dates,r.effective_date,b.effective_until) or(r.ordinal>1 and(p.ordinal is null or r.effective_date<=p.effective_date or r.recorded_at<p.recorded_at))"
        report["integrityChecks"] = {key: int(sql(target, statement).splitlines()[-1]) for key, statement in checks.items()}
        if any(report["integrityChecks"].values()):
            raise RuntimeError("Restored integrity checks failed")
        report["employeeAuditSelectionIndex"] = int(sql(target, "select count(*) from pg_indexes where schemaname='atlas_acceptance' and indexname='hr_audit_employee_recorded_id'")) == 1
        if not report["employeeAuditSelectionIndex"]:
            raise RuntimeError("Employee audit selection index missing")
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
        sql(target, """DO $drill$ BEGIN
          BEGIN
            UPDATE atlas_acceptance.hr_case_episode SET linked_by='restore probe' WHERE request_id=(SELECT min(request_id) FROM atlas_acceptance.hr_case_episode);
            RAISE EXCEPTION 'episode guard did not reject update' USING ERRCODE='ZX001';
          EXCEPTION WHEN raise_exception THEN
            IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF;
          END;
        END $drill$;""")
        report["caseEpisodeMutationRejected"] = True
        if not after["hr_access_grant_episode"]["rows"]:
            raise RuntimeError("Drill requires synthetic episode-owned grant history")
        sql(target, """DO $drill$ BEGIN
          BEGIN
            UPDATE atlas_acceptance.hr_access_grant_episode SET episode_start_date=episode_start_date+1 WHERE grant_id=(SELECT min(grant_id) FROM atlas_acceptance.hr_access_grant_episode);
            RAISE EXCEPTION 'grant episode guard did not reject update' USING ERRCODE='ZX001';
          EXCEPTION WHEN raise_exception THEN
            IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF;
          END;
        END $drill$;""")
        report["grantEpisodeMutationRejected"] = True
        if int(sql(target, "select count(*) from atlas_acceptance.benefit_selection where status='WITHDRAWN'")) > 0:
            sql(target, """DO $drill$ BEGIN
              BEGIN
                UPDATE atlas_acceptance.benefit_selection_audit SET reason=reason WHERE id=(SELECT min(id) FROM atlas_acceptance.benefit_selection_audit WHERE action='WITHDRAWN');
                RAISE EXCEPTION 'benefit audit guard did not reject update' USING ERRCODE='ZX001';
              EXCEPTION WHEN raise_exception THEN
                IF SQLERRM <> 'Benefits configuration is append only' THEN RAISE; END IF;
              END;
            END $drill$;""")
            report["benefitWithdrawalAuditMutationRejected"] = True
        if after["hr_legal_entity"]["rows"]:
            for statement in ["update atlas_acceptance.hr_legal_entity set reason=reason", "truncate atlas_acceptance.hr_legal_entity cascade"]:
                sql(target, "DO $drill$ BEGIN BEGIN " + statement + "; RAISE EXCEPTION 'legal entity guard did not reject mutation' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF; END; END $drill$;")
            report["legalEntityMutationRejected"] = True
        if after["hr_legal_entity_retirement_decision"]["rows"]:
            for statement in ["update atlas_acceptance.hr_legal_entity_retirement_request set reason=reason", "update atlas_acceptance.hr_legal_entity_retirement_decision set comment=comment", "truncate atlas_acceptance.hr_legal_entity_retirement_request cascade", "truncate atlas_acceptance.hr_legal_entity_retirement_decision"]:
                sql(target, "DO $drill$ BEGIN BEGIN " + statement + "; RAISE EXCEPTION 'retirement guard did not reject mutation' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF; END; END $drill$;")
            report["referenceRetirementMutationRejected"] = True
        if after["hr_work_calendar"]["rows"]:
            for statement in ["update atlas_acceptance.hr_work_calendar set reason=reason", "delete from atlas_acceptance.hr_work_calendar", "truncate atlas_acceptance.hr_work_calendar cascade"]:
                sql(target, "set search_path=pg_catalog; DO $drill$ BEGIN BEGIN " + statement + "; RAISE EXCEPTION 'calendar guard did not reject mutation' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF; END; END $drill$;")
            report["companyCalendarMutationRejected"] = True
        if after["hr_work_calendar_revision"]["rows"]:
            for statement in ["update atlas_acceptance.hr_work_calendar_revision set reason=reason", "delete from atlas_acceptance.hr_work_calendar_revision", "truncate atlas_acceptance.hr_work_calendar_revision"]:
                sql(target, "set search_path=pg_catalog; DO $drill$ BEGIN BEGIN " + statement + "; RAISE EXCEPTION 'calendar amendment guard did not reject mutation' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF; END; END $drill$;")
            report["calendarAmendmentMutationRejected"] = True
        if after["hr_work_calendar_retirement"]["rows"]:
            for statement in ["update atlas_acceptance.hr_work_calendar_retirement set reason=reason", "delete from atlas_acceptance.hr_work_calendar_retirement", "truncate atlas_acceptance.hr_work_calendar_retirement"]:
                sql(target, "set search_path=pg_catalog; DO $drill$ BEGIN BEGIN " + statement + "; RAISE EXCEPTION 'calendar retirement guard did not reject mutation' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF; END; END $drill$;")
            report["calendarRetirementMutationRejected"] = True
        if after["hr_calendar_assignment"]["rows"]:
            for table in ["hr_calendar_assignment", "hr_calendar_request_policy"]:
                for statement in ["update atlas_acceptance." + table + " set recorded_at=recorded_at", "delete from atlas_acceptance." + table, "truncate atlas_acceptance." + table]:
                    sql(target, "set search_path=pg_catalog; DO $drill$ BEGIN BEGIN " + statement + "; RAISE EXCEPTION 'assignment guard did not reject mutation' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF; END; END $drill$;")
            report["calendarAssignmentMutationRejected"] = True
        if after["hr_legal_entity_assignment"]["rows"]:
            for table in ["hr_legal_entity_assignment", "hr_entity_request_origin"]:
                for statement in ["update atlas_acceptance." + table + " set recorded_at=recorded_at", "delete from atlas_acceptance." + table, "truncate atlas_acceptance." + table]:
                    sql(target, "set search_path=pg_catalog; DO $drill$ BEGIN BEGIN " + statement + "; RAISE EXCEPTION 'entity guard did not reject mutation' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF; END; END $drill$;")
            sql(target,"set search_path=pg_catalog; DO $drill$ BEGIN BEGIN update atlas_acceptance.hr_request set new_value='FORGED' where field='legalEntityCode'; RAISE EXCEPTION 'entity request guard did not reject mutation' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM <> 'Entity request provenance is immutable' THEN RAISE; END IF; END; END $drill$;")
            report["entityAssignmentMutationRejected"] = True
        if after["hr_department_parent_change"]["rows"]:
            for statement in ["update atlas_acceptance.hr_department_parent_change set reason=reason", "delete from atlas_acceptance.hr_department_parent_change", "truncate atlas_acceptance.hr_department_parent_change"]:
                sql(target, "set search_path=pg_catalog; DO $drill$ BEGIN BEGIN " + statement + "; RAISE EXCEPTION 'parent correction guard did not reject mutation' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF; END; END $drill$;")
            report["departmentParentMutationRejected"] = True
        for table, assignment in [("time_audit","reason=reason"),("time_command","fingerprint=fingerprint"),("time_costing_entry","minutes=minutes"),("time_period","snapshot=snapshot"),("time_batch_command","fingerprint=fingerprint")]:
            if after[table]["rows"]:
                for statement in ["update atlas_acceptance."+table+" set "+assignment,"delete from atlas_acceptance."+table,"truncate atlas_acceptance."+table]:
                    sql(target,"DO $drill$ BEGIN BEGIN "+statement+"; RAISE EXCEPTION 'time guard did not reject mutation' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF; END; END $drill$;")
        report["timekeepingImmutableGuardsPassed"] = True
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
