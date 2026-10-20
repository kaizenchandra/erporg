-- Additional invariants without rewriting the already-applied foundation migration.
create function pay_result_provenance_guard() returns trigger language plpgsql as $$
declare s jsonb; e jsonb; dates date[]='{}'; total integer=0; d date; m integer;
begin
 s:=new.snapshot;
 if coalesce(btrim(s->>'reason'),'')='' or coalesce(s->>'preparedBy','')='' or coalesce(s->>'preparedEmployee','')='' or s->'source'->>'employeeId' is distinct from new.employee_id or s->'source'->>'episodeStart' is distinct from s->>'episodeStart' then raise exception 'Missing payroll provenance';end if;
 if jsonb_array_length(s->'source'->'days') is distinct from 7 then raise exception 'Seven source dates required';end if;
 for e in select value from jsonb_array_elements(s->'source'->'days') loop
  d:=(e->>'date')::date;m:=(e->>'minutes')::integer;
  if d is null or d=any(dates) or d<(s->'source'->>'week')::date or d>=(s->'source'->>'week')::date+7 or m is null or m<0 or m>1440 or (e->>'minutes')::numeric<>m then raise exception 'Invalid payroll source dates or minutes';end if;
  dates:=array_append(dates,d);total:=total+m;
 end loop;
 if total is distinct from (s->'source'->>'totalMinutes')::integer then raise exception 'Payroll source total mismatch';end if;
 if tg_op='INSERT' and (s->>'approvedBy' is not null or s->>'approvedEmployee' is not null or s->>'journalId' is not null) then raise exception 'New payroll cannot carry approval or posting';end if;
 if tg_op='UPDATE' then
  if not(old.status='SUBMITTED' and new.status='FINALIZED') and (s->>'approvedBy' is distinct from old.snapshot->>'approvedBy' or s->>'approvedEmployee' is distinct from old.snapshot->>'approvedEmployee') then raise exception 'Frozen payroll approval identity';end if;
  if not(old.status='FINALIZED' and new.status='FINALIZED' and old.snapshot->>'journalId' is null and s->>'journalId' is not null) and s->>'journalId' is distinct from old.snapshot->>'journalId' then raise exception 'Accounting link requires finalized payroll';end if;
 end if;
 return new;
end $$;
create trigger pay_result_provenance before insert or update on pay_run for each row execute function pay_result_provenance_guard();
create function fin_document_provenance_guard() returns trigger language plpgsql as $$
begin
 if coalesce(btrim(new.snapshot->>'reason'),'')='' or coalesce(new.snapshot->>'preparedBy','')='' or coalesce(new.snapshot->>'preparedEmployee','')='' then raise exception 'Missing journal provenance';end if;
 if (new.source_type='REVERSAL') is distinct from (new.snapshot->>'reversalOf' is not null) then raise exception 'Invalid reversal provenance';end if;
 if new.source_type='REVERSAL' and new.snapshot->>'reversalOf' is distinct from new.source_id then raise exception 'Invalid reversal source';end if;
 if tg_op='INSERT' and (new.snapshot->>'approvedBy' is not null or new.snapshot->>'approvedEmployee' is not null) then raise exception 'New journal cannot carry an approval';end if;
 return new;
end $$;
create trigger fin_document_provenance before insert or update on fin_journal for each row execute function fin_document_provenance_guard();
