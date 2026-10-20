"""Reject an unsupported source reversal inside a rollback-safe local synthetic transaction."""
import argparse
import json
from pathlib import Path
import re
import restore_check
from verify_financial_records import TABLES

def probe(schema):
    if not re.fullmatch(r"atlas_payfin_[a-z0-9_]+",schema):
        raise ValueError("Only local synthetic financial schemas are supported")
    restore_check.SCHEMA=schema
    before=restore_check._batch_manifest("atlas",TABLES)
    statement=f"""
    set search_path=pg_catalog;
    do $probe$
    declare original {schema}.fin_journal%rowtype; probe_id text;
    begin
      select * into original from {schema}.fin_journal where source_type='REVERSAL' and status='POSTED' limit 1;
      if original.id is null then raise exception 'Posted synthetic reversal fixture required';end if;
      probe_id:='probe-'||substring(md5(random()::text),1,24);
      begin
        insert into {schema}.fin_journal(tenant,id,entity,posting_date,source_type,source_id,status,version,snapshot)
        values(original.tenant,probe_id,original.entity,original.posting_date,'REVERSAL',original.id,'DRAFT',0,
          original.snapshot||jsonb_build_object('id',probe_id,'sourceId',original.id,'reversalOf',original.id,'status','DRAFT','version',0,'approvedBy',null,'approvedEmployee',null));
        raise exception 'Guard failed to reject unsupported source' using errcode='ZX001';
      exception when raise_exception then
        if sqlerrm<>'Only posted original manual journals may create reversal drafts' then raise;end if;
      end;
    end $probe$;
    """
    restore_check.sql("atlas",statement)
    after=restore_check._batch_manifest("atlas",TABLES)
    if before!=after:
        raise RuntimeError("Probe changed financial history")
    return {"result":"PASSED","schema":schema,"scope":"Transactional rejected-insert probe, not a payment or production posting","specificSourceGuardRejected":True,"callerSchema":"pg_catalog","financialTableManifestsUnchanged":len(TABLES)}

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--schema",required=True)
    parser.add_argument("--output",required=True,type=Path)
    args=parser.parse_args()
    if args.output.exists():raise SystemExit("Refusing to overwrite prior evidence")
    report=probe(args.schema)
    with args.output.open("x") as file:
        json.dump(report,file,indent=2);file.write("\n")
    print(json.dumps(report))
