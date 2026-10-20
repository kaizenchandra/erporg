"""Read-only checks for local synthetic talent snapshots; not restore or release certification."""
import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
import restore_check

TABLES = ["tal_requisition", "tal_onboarding", "tal_review", "tal_course", "tal_enrollment", "tal_compensation", "tal_succession", "tal_scenario", "tal_command", "tal_audit", "pay_talent_intake"]
KINDS = ["requisitions", "onboarding", "reviews", "courses", "learning", "compensation", "succession", "scenarios"]

def verify(schema):
    if not re.fullmatch(r"atlas_(talent|payfin)_[a-z0-9_]+", schema):
        raise ValueError("Only explicitly named local synthetic talent/finance schemas are supported")
    records = " union all ".join(f"select tenant,id,snapshot,'{kind}' as kind from {schema}.{table}" for table,kind in zip(TABLES[:8], KINDS))
    query = f"""
    with records as ({records}) select json_build_object(
      'missingLatestAudit',(select count(*) from records r where not exists(select 1 from {schema}.tal_audit a where a.tenant=r.tenant and a.kind=r.kind and a.record_id=r.id and (a.snapshot->>'after')::jsonb=r.snapshot)),
      'invalidCompensationBudget',(select count(*) from {schema}.tal_compensation c where (select sum((p->>'proposedSalary')::numeric-(p->>'currentSalary')::numeric+(p->>'bonus')::numeric) from jsonb_array_elements(c.snapshot->'proposals') p)<>(c.snapshot->>'total')::numeric or (c.snapshot->>'total')::numeric>(c.snapshot->>'budget')::numeric),
      'missingPayrollIntake',(select count(*) from {schema}.tal_compensation c where c.snapshot->'meta'->>'status'='APPROVED' and not exists(select 1 from {schema}.pay_talent_intake p where p.tenant=c.tenant and p.source_id=c.id and p.id=c.snapshot->>'payrollIntakeId' and p.snapshot->'awards'=c.snapshot->'proposals' and p.snapshot->>'currency'=c.snapshot->>'currency' and p.snapshot->>'status'='RECEIVED')),
      'invalidLearningExpiry',(select count(*) from {schema}.tal_enrollment e where e.snapshot->'meta'->>'status'='COMPLETED' and ((e.snapshot->>'completed') is null or ((e.snapshot->>'validDays')::int>0 and (e.snapshot->>'expires')::date is distinct from (e.snapshot->>'completed')::date+(e.snapshot->>'validDays')::int) or ((e.snapshot->>'validDays')::int=0 and e.snapshot->>'expires' is not null))),
      'invalidCompletedReview',(select count(*) from {schema}.tal_review r where r.snapshot->'meta'->>'status'='COMPLETED' and (r.snapshot->>'rating' is null or (r.snapshot->>'rating')::int not between (r.snapshot->>'minimumRating')::int and (r.snapshot->>'maximumRating')::int)),
      'incompleteActivatedPlan',(select count(*) from {schema}.tal_onboarding p where p.snapshot->'meta'->>'status'='COMPLETED' and (coalesce(p.snapshot->>'joiningForm','')='' or jsonb_array_length(p.snapshot->'tasks')=0 or exists(select 1 from jsonb_array_elements(p.snapshot->'tasks') t where (t->>'complete')::boolean is distinct from true))),
      'invalidScenarioCost',(select count(*) from {schema}.tal_scenario s where (s.snapshot->>'baselineHeadcount')::int<>jsonb_array_length(s.snapshot->'workerSnapshot') or (select sum((l->>'baselineHeadcount')::numeric*(l->>'annualUnitCost')::numeric) from jsonb_array_elements(s.snapshot->'lines') l)<>(s.snapshot->>'baselineCost')::numeric or (select sum((l->>'proposedHeadcount')::numeric*(l->>'annualUnitCost')::numeric) from jsonb_array_elements(s.snapshot->'lines') l)<>(s.snapshot->>'proposedCost')::numeric or (s.snapshot->>'proposedCost')::numeric-(s.snapshot->>'baselineCost')::numeric<>(s.snapshot->>'variance')::numeric),
      'completedJoiningPlans',(select count(*) from {schema}.tal_onboarding where snapshot->'meta'->>'status'='COMPLETED'),
      'completedReviews',(select count(*) from {schema}.tal_review where snapshot->'meta'->>'status'='COMPLETED'),
      'completedLearning',(select count(*) from {schema}.tal_enrollment where snapshot->'meta'->>'status'='COMPLETED'),
      'approvedCompensation',(select count(*) from {schema}.tal_compensation where snapshot->'meta'->>'status'='APPROVED'),
      'enabledGuards',(select count(*) from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname='{schema}' and not t.tgisinternal and t.tgenabled='O' and (c.relname like 'tal_%' or c.relname='pay_talent_intake'))
    )
    """
    controls = json.loads(restore_check.sql("atlas", query))
    failures = [k for k,v in controls.items() if k.startswith(("missing", "invalid", "incomplete")) and v != 0]
    if controls["enabledGuards"] < 27 or any(controls[k]<1 for k in ["completedJoiningPlans","completedReviews","completedLearning","approvedCompensation"]):
        failures.append("missing-workflow-fixture-or-integrity-guards")
    restore_check.SCHEMA = schema
    manifests = restore_check._batch_manifest("atlas", TABLES)
    return {"recordedAt":datetime.now(timezone.utc).isoformat(),"schema":schema,"scope":__doc__,"result":"FAILED" if failures else "PASSED","controls":controls,"failures":failures,"tableManifests":manifests}

if __name__ == "__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--schema",required=True)
    p.add_argument("--output",type=Path,required=True)
    args=p.parse_args()
    if args.output.exists():raise SystemExit("Refusing to overwrite prior evidence")
    report=verify(args.schema)
    with args.output.open("x") as f:json.dump(report,f,indent=2);f.write("\n")
    print(json.dumps({"result":report["result"],"controls":report["controls"]}))
    raise SystemExit(0 if report["result"]=="PASSED" else 1)
