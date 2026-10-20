"""Read-only synthetic financial reconciliation. This is NOT a backup/restore or bank test."""
import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
import restore_check

TABLES = ["fin_grant", "fin_ledger", "fin_period", "fin_journal", "fin_command", "fin_audit", "pay_run", "pay_command", "pay_audit"]

def verify(schema):
    if not re.fullmatch(r"atlas_payfin_[a-z0-9_]+", schema):
        raise ValueError("Only explicitly named local synthetic payroll/finance schemas are supported")
    # One statement/snapshot for all business checks. No record values appear in the report.
    query = f"""
    with result as (
    select json_build_object(
      'invalidPayrollTotals',(select count(*) from {schema}.pay_run where
        (snapshot->>'gross')::numeric-(snapshot->>'deduction')::numeric<>(snapshot->>'net')::numeric
        or (snapshot->>'gross')::numeric<>{schema}.pay_round_even((snapshot->'source'->>'totalMinutes')::numeric*(snapshot->>'hourlyRate')::numeric/60)),
      'invalidJournalTotals',(select count(*) from {schema}.fin_journal j where
        (select sum((e->>'debit')::numeric-(e->>'credit')::numeric) from jsonb_array_elements(j.snapshot->'lines') e)<>0),
      'invalidPayrollJournalLinks',(select count(*) from {schema}.pay_run p where p.snapshot->>'journalId' is not null and not exists(
        select 1 from {schema}.fin_journal j where j.tenant=p.tenant and j.entity=p.entity and j.id=p.snapshot->>'journalId'
        and j.source_type='PAYROLL' and j.source_id=p.id and p.status='FINALIZED' and j.snapshot->>'currency'=p.snapshot->>'currency'
        and (select sum((e->>'debit')::numeric) from jsonb_array_elements(j.snapshot->'lines') e)=(p.snapshot->>'gross')::numeric
        and (select coalesce(sum((e->>'credit')::numeric),0) from jsonb_array_elements(j.snapshot->'lines') e where e->>'account'='2200')=(p.snapshot->>'net')::numeric
        and (select coalesce(sum((e->>'credit')::numeric),0) from jsonb_array_elements(j.snapshot->'lines') e where e->>'account'='2100')=(p.snapshot->>'deduction')::numeric)),
      'invalidJournalApprovalIndependence',(select count(*) from {schema}.fin_journal where status in('APPROVED','POSTED','REVERSED') and
        (snapshot->>'approvedEmployee' is null or snapshot->>'approvedEmployee'=snapshot->>'preparedEmployee')),
      'invalidPayrollApprovalIndependence',(select count(*) from {schema}.pay_run where status='FINALIZED' and
        (snapshot->>'approvedEmployee' is null or snapshot->>'approvedEmployee'=snapshot->>'preparedEmployee' or snapshot->>'approvedEmployee'=employee_id)),
      'falselyClaimedPayment',(select count(*) from {schema}.pay_run where snapshot->>'paymentStatus'<>'NOT_CONFIGURED'),
      'reversedWithoutPostedCounterEntry',(select count(*) from {schema}.fin_journal j where status='REVERSED' and not exists(
        select 1 from {schema}.fin_journal r where r.tenant=j.tenant and r.entity=j.entity and r.source_type='REVERSAL' and r.source_id=j.id and r.status='POSTED')),
      'postedJournals',(select count(*) from {schema}.fin_journal where status in('POSTED','REVERSED')),
      'finalizedPayrollRuns',(select count(*) from {schema}.pay_run where status='FINALIZED'),
      'enabledIntegrityGuards',(select count(*) from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
        where n.nspname='{schema}' and not t.tgisinternal and t.tgenabled='O' and c.relname in('fin_journal','fin_period','fin_ledger','fin_command','fin_audit','pay_run','pay_command','pay_audit'))
    ) as data) select data from result
    """
    controls = json.loads(restore_check.sql("atlas", query))
    failures = [k for k,v in controls.items() if (k.startswith("invalid") or k in {"falselyClaimedPayment","reversedWithoutPostedCounterEntry"}) and v != 0]
    if controls["enabledIntegrityGuards"] < 20 or controls["postedJournals"] < 1 or controls["finalizedPayrollRuns"] < 1:
        failures.append("missing-fixture-or-integrity-guards")
    restore_check.SCHEMA = schema
    manifests = restore_check._batch_manifest("atlas", TABLES)
    return {"recordedAt":datetime.now(timezone.utc).isoformat(),"schema":schema,"scope":"Read-only quiescent synthetic record checks; not payment-provider or restore verification", "result":"FAILED" if failures else "PASSED", "controls":controls,"failures":failures,"tableManifests":manifests}

if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--schema",required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists():
        raise SystemExit("Refusing to overwrite prior evidence")
    report=verify(args.schema)
    with args.output.open("x") as file:
        json.dump(report,file,indent=2)
        file.write("\n")
    print(json.dumps({"result":report["result"],"controls":report["controls"]}))
    raise SystemExit(0 if report["result"]=="PASSED" else 1)
