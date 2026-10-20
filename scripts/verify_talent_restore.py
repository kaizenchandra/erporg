"""Isolated local synthetic schema restore. Never replaces or drops a database."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import time
from uuid import uuid4
import restore_check


def run(schema, output):
    if not re.fullmatch(r"atlas_(talent|payfin)_[a-z0-9_]+", schema):
        raise ValueError("Only explicitly named local synthetic schemas are supported")
    if output.exists():
        raise ValueError("Refusing to overwrite prior evidence")
    target = "atlas_talent_restore_" + uuid4().hex[:12]
    report = {"recordedAt":datetime.now(timezone.utc).isoformat(),"sourceSchema":schema,"targetDatabase":target,"scope":"Actual local synthetic full-schema logical restore; no production RPO/RTO claim","result":"FAILED"}
    started = time.monotonic()
    try:
        tables = restore_check.sql("atlas",f"select tablename from pg_tables where schemaname='{schema}' and tablename not in ('spring_session','spring_session_attributes') order by tablename").splitlines()
        views = restore_check.sql("atlas",f"select viewname from pg_views where schemaname='{schema}' order by viewname").splitlines()
        if not all(t in tables for t in ['tal_audit','tal_succession','pay_talent_intake','fin_journal','hr_employee']):
            raise RuntimeError("Talent, payroll, finance and Core HR fixture tables are required")
        restore_check.SCHEMA=schema
        before=restore_check._batch_manifest("atlas",tables+views)
        dump=restore_check.command(['pg_dump','-U','atlas','-d','atlas','-Fc','--schema='+schema,'--exclude-table-data='+schema+'.spring_session','--exclude-table-data='+schema+'.spring_session_attributes','--no-owner','--no-acl'])
        report.update(backupBytes=len(dump),backupSha256=hashlib.sha256(dump).hexdigest())
        restore_check.command(['createdb','-U','atlas',target])
        restore_started=time.monotonic()
        restore_check.command(['pg_restore','-U','atlas','-d',target,'--exit-on-error','--no-owner','--no-acl'],dump)
        report['restoreSeconds']=round(time.monotonic()-restore_started,3)
        after=restore_check._batch_manifest(target,tables+views)
        if before!=after:raise RuntimeError("Source and recovered table/view manifests differ")
        probe=f"""DO $probe$ BEGIN
        BEGIN
          UPDATE {schema}.tal_audit SET snapshot=snapshot;
          RAISE EXCEPTION 'Restored audit guard did not reject mutation' USING ERRCODE='ZX001';
        EXCEPTION WHEN raise_exception THEN
          IF SQLERRM <> 'Talent history and command receipts cannot be changed or deleted' THEN RAISE; END IF;
        END;
        END $probe$;"""
        restore_check.sql(target,probe)
        if restore_check._batch_manifest(target,tables+views)!=after:raise RuntimeError("Guard probe changed recovered records")
        report.update(result='PASSED',tablesCompared=len(tables),viewsCompared=len(views),manifests=after,restoredAuditMutationRejected=True,sessionRowsExcluded=True)
    except Exception as exc:
        # No record contents, credentials or database error detail in public evidence.
        report['errorType']=type(exc).__name__
        raise
    finally:
        report['totalSeconds']=round(time.monotonic()-started,3)
        with output.open('x') as f:json.dump(report,f,indent=2);f.write('\n')
        print(json.dumps({k:v for k,v in report.items() if k!='manifests'}))
    return report

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--schema',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    run(args.schema,args.output)
