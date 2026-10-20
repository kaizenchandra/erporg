-- Independent, demonstration-only payroll and ledger foundation. No statutory rules or bank adapters.
-- Each module owns its records; cross-module consistency uses published synchronous contracts.
create table fin_grant(tenant varchar(80) not null,subject varchar(120) not null,employee_id varchar(40) not null,entity varchar(30) not null,permission varchar(30) not null check(permission in('READ','SETUP','PREPARE','APPROVE','POST','PAYROLL_PREPARE','PAYROLL_APPROVE')),primary key(tenant,subject,employee_id,entity,permission));
create table fin_ledger(tenant varchar(80) not null,entity varchar(30) not null,snapshot jsonb not null,primary key(tenant,entity),check(snapshot->>'entity'=entity and snapshot->>'currency'='USD'));
create table fin_period(tenant varchar(80) not null,entity varchar(30) not null,month date not null,version bigint not null check(version between 1 and 10000),snapshot jsonb not null,primary key(tenant,entity,month,version),foreign key(tenant,entity) references fin_ledger,check(extract(day from month)=1));
create table fin_journal(tenant varchar(80) not null,id varchar(40) not null,entity varchar(30) not null,posting_date date not null,source_type varchar(20) not null check(source_type in('MANUAL','PAYROLL','REVERSAL')),source_id varchar(40) not null,status varchar(20) not null check(status in('DRAFT','SUBMITTED','APPROVED','POSTED','REVERSED','RETURNED','REJECTED')),version bigint not null check(version>=0),snapshot jsonb not null,primary key(tenant,id),foreign key(tenant,entity) references fin_ledger,unique(tenant,entity,source_type,source_id));
create index fin_journal_directory on fin_journal(tenant,entity,status,posting_date desc,id);
create table fin_command(tenant varchar(80) not null,subject varchar(120) not null,command_key varchar(80) not null,fingerprint char(64) not null,result jsonb not null,primary key(tenant,subject,command_key));
create table fin_audit(id bigint generated always as identity primary key,tenant varchar(80) not null,entity varchar(30) not null,document_id varchar(40) not null,action varchar(30) not null,actor varchar(120) not null,reason varchar(500) not null,before_snapshot jsonb not null,after_snapshot jsonb not null,recorded_at timestamptz not null,correlation_id varchar(80) not null);
create index fin_audit_directory on fin_audit(tenant,entity,id desc);
create table pay_run(tenant varchar(80) not null,id varchar(40) not null,entity varchar(30) not null,employee_id varchar(40) not null,source_id varchar(40) not null,status varchar(20) not null check(status in('CALCULATED','SUBMITTED','RETURNED','FINALIZED')),version bigint not null check(version>=0),snapshot jsonb not null,primary key(tenant,id),unique(tenant,source_id));
create index pay_run_directory on pay_run(tenant,entity,employee_id,status,id);
create table pay_command(tenant varchar(80) not null,subject varchar(120) not null,command_key varchar(80) not null,fingerprint char(64) not null,result jsonb not null,primary key(tenant,subject,command_key));
create table pay_audit(id bigint generated always as identity primary key,tenant varchar(80) not null,run_id varchar(40) not null,actor varchar(120) not null,action varchar(30) not null,reason varchar(500) not null,before_snapshot jsonb not null,after_snapshot jsonb not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,foreign key(tenant,run_id) references pay_run);
create index pay_audit_directory on pay_audit(tenant,run_id,id desc);
create function fin_period_guard() returns trigger language plpgsql as $$
begin
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/finance/'||new.tenant||'/'||new.entity,0));
 if new.version<>(select coalesce(max(version),0)+1 from fin_period where tenant=new.tenant and entity=new.entity and month=new.month) or new.snapshot->>'month' is distinct from new.month::text or (new.snapshot->>'version')::numeric is distinct from new.version or jsonb_typeof(new.snapshot->'closed') is distinct from 'boolean' then raise exception 'Invalid fiscal period revision';end if;
 return new;
end $$;
create trigger fin_period_integrity before insert on fin_period for each row execute function fin_period_guard();
create function fin_journal_guard() returns trigger language plpgsql as $$
declare l jsonb;e jsonb;a jsonb;d numeric;c numeric;debits numeric=0;credits numeric=0;
begin
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/finance/'||new.tenant||'/'||new.entity,0));
 select snapshot into l from fin_ledger where tenant=new.tenant and entity=new.entity;
 if l is null or new.snapshot->>'id' is distinct from new.id or new.snapshot->>'entity' is distinct from new.entity or new.snapshot->>'currency' is distinct from l->>'currency' or new.snapshot->>'status' is distinct from new.status or new.snapshot->>'sourceType' is distinct from new.source_type or new.snapshot->>'sourceId' is distinct from new.source_id or (new.snapshot->>'postingDate')::date is distinct from new.posting_date or (new.snapshot->>'version')::numeric is distinct from new.version then raise exception 'Inconsistent journal snapshot';end if;
 if new.posting_date<(l->>'effectiveFrom')::date or new.posting_date>(current_timestamp at time zone 'UTC')::date then raise exception 'Invalid posting date';end if;
 if tg_op='INSERT' and (new.status<>'DRAFT' or new.version<>0) then raise exception 'Invalid initial journal';end if;
 if tg_op='UPDATE' then
  if new.tenant<>old.tenant or new.id<>old.id or new.entity<>old.entity or new.source_type<>old.source_type or new.source_id<>old.source_id or new.version<>old.version+1 or (new.snapshot->>'preparedBy') is distinct from old.snapshot->>'preparedBy' or (new.snapshot->>'preparedEmployee') is distinct from old.snapshot->>'preparedEmployee' or (new.snapshot->>'reversalOf') is distinct from old.snapshot->>'reversalOf' then raise exception 'Invalid journal revision identity';end if;
  if not ((old.status in('DRAFT','RETURNED','REJECTED') and new.status in('DRAFT','SUBMITTED')) or(old.status='SUBMITTED' and new.status in('APPROVED','RETURNED','REJECTED')) or(old.status='APPROVED' and new.status='POSTED') or(old.status='POSTED' and new.status='REVERSED')) then raise exception 'Invalid journal transition';end if;
  if not(old.status in('DRAFT','RETURNED','REJECTED') and new.status='DRAFT') and (new.snapshot->'lines' is distinct from old.snapshot->'lines' or new.posting_date<>old.posting_date) then raise exception 'Frozen journal content';end if;
  if old.source_type<>'MANUAL' and new.snapshot->'lines' is distinct from old.snapshot->'lines' then raise exception 'Source journal lines are immutable';end if;
  if new.status<>'APPROVED' and (new.snapshot->>'approvedBy' is distinct from old.snapshot->>'approvedBy' or new.snapshot->>'approvedEmployee' is distinct from old.snapshot->>'approvedEmployee') then raise exception 'Invalid approval identity change';end if;
 end if;
 if new.status in('APPROVED','POSTED','REVERSED') and (coalesce(new.snapshot->>'approvedEmployee','')='' or new.snapshot->>'approvedEmployee'=new.snapshot->>'preparedEmployee' or new.snapshot->>'approvedBy'=new.snapshot->>'preparedBy') then raise exception 'Independent journal approval required';end if;
 if new.status in('DRAFT','SUBMITTED','APPROVED','POSTED') and coalesce((select (snapshot->>'closed')::boolean from fin_period where tenant=new.tenant and entity=new.entity and month=date_trunc('month',new.posting_date)::date order by version desc limit 1),false) then raise exception 'Closed posting period';end if;
 if jsonb_typeof(new.snapshot->'lines') is distinct from 'array' or jsonb_array_length(new.snapshot->'lines') not between 2 and 100 then raise exception 'Invalid journal lines';end if;
 for e in select value from jsonb_array_elements(new.snapshot->'lines') loop
  select value into a from jsonb_array_elements(l->'accounts') where value->>'code'=e->>'account';
  if a is null then raise exception 'Invalid account';end if;
  if (a->>'requiresCostCentre')::boolean and coalesce(e->>'costCentre','')='' then raise exception 'Missing expense dimension';end if;
  if coalesce(e->>'costCentre','')<>'' and not(l->'costCentres' ? (e->>'costCentre')) then raise exception 'Invalid cost centre';end if;
  d:=(e->>'debit')::numeric;c:=(e->>'credit')::numeric;
  if d is null or c is null or d<0 or c<0 or d>999999999999.99 or c>999999999999.99 or d<>round(d,2) or c<>round(c,2) or ((d>0)=(c>0)) then raise exception 'Invalid decimal journal amounts';end if;
  debits:=debits+d;credits:=credits+c;
 end loop;
 if debits<=0 or debits<>credits then raise exception 'Unbalanced journal';end if;
 return new;
end $$;
create trigger fin_journal_integrity before insert or update on fin_journal for each row execute function fin_journal_guard();
create function fin_reversal_guard() returns trigger language plpgsql as $$
declare j jsonb;r jsonb;
begin
 if new.status='REVERSED' then
  select snapshot into r from fin_journal where tenant=new.tenant and entity=new.entity and source_type='REVERSAL' and source_id=new.id and status='POSTED';
  if r is null then raise exception 'Reversed journal requires a posted reversal';end if;
 end if;
 if new.source_type='REVERSAL' and new.status='POSTED' then
  select snapshot into j from fin_journal where tenant=new.tenant and entity=new.entity and id=new.source_id and status='REVERSED';
  if j is null or new.snapshot->>'reversalOf' is distinct from new.source_id or
   (select jsonb_agg(jsonb_build_array(x->>'account',x->>'costCentre',(x->>'credit')::numeric,(x->>'debit')::numeric) order by n) from jsonb_array_elements(j->'lines') with ordinality t(x,n)) is distinct from
   (select jsonb_agg(jsonb_build_array(x->>'account',x->>'costCentre',(x->>'debit')::numeric,(x->>'credit')::numeric) order by n) from jsonb_array_elements(new.snapshot->'lines') with ordinality t(x,n)) then raise exception 'Reversal lines must exactly counter original';end if;
 end if;
 return null;
end $$;
create constraint trigger fin_reversal_integrity after insert or update on fin_journal deferrable initially deferred for each row execute function fin_reversal_guard();
create function pay_round_even(v numeric) returns numeric language sql immutable strict as $$select (trunc(v*100)+case when v*100-trunc(v*100)>.5 or (v*100-trunc(v*100)=.5 and mod(trunc(v*100),2)=1) then 1 else 0 end)/100$$;
create function pay_run_guard() returns trigger language plpgsql as $$
declare s jsonb;gross numeric;deduction numeric;net numeric;minutes numeric;rate numeric;
begin
 s:=new.snapshot;
 if s->>'id' is distinct from new.id or s->>'entity' is distinct from new.entity or s->>'employeeId' is distinct from new.employee_id or s->'source'->>'id' is distinct from new.source_id or s->>'status' is distinct from new.status or (s->>'version')::numeric is distinct from new.version or s->>'currency' is distinct from 'USD' or s->>'ruleVersion' is distinct from 'DEMO-HOURLY-1;USD-2DP-HALF-EVEN;NO-STATUTORY-RULES' or s->>'paymentStatus' is distinct from 'NOT_CONFIGURED' then raise exception 'Invalid demonstration payroll snapshot';end if;
 gross:=(s->>'gross')::numeric;deduction:=(s->>'deduction')::numeric;net:=(s->>'net')::numeric;minutes:=(s->'source'->>'totalMinutes')::numeric;rate:=(s->>'hourlyRate')::numeric;
 if minutes is null or minutes<>trunc(minutes) or minutes not between 1 and 10080 or rate is null or rate<=0 or rate>1000000 or rate<>round(rate,4) or gross is null or deduction is null or net is null or deduction<0 or deduction>999999999999.99 or deduction<>round(deduction,2) or gross<>pay_round_even(minutes*rate/60) or gross-deduction<>net then raise exception 'Invalid payroll control totals';end if;
 if tg_op='INSERT' and (new.status<>'CALCULATED' or new.version<>0) then raise exception 'Invalid initial payroll';end if;
 if tg_op='UPDATE' then
  if new.tenant<>old.tenant or new.id<>old.id or new.entity<>old.entity or new.employee_id<>old.employee_id or new.source_id<>old.source_id or new.version<>old.version+1 or s->>'preparedEmployee' is distinct from old.snapshot->>'preparedEmployee' or s->>'preparedBy' is distinct from old.snapshot->>'preparedBy' or s->'source' is distinct from old.snapshot->'source' or s->>'episodeStart' is distinct from old.snapshot->>'episodeStart' then raise exception 'Invalid payroll revision';end if;
  if not((old.status in('CALCULATED','RETURNED') and new.status in('CALCULATED','SUBMITTED'))or(old.status='SUBMITTED' and new.status in('RETURNED','FINALIZED'))or(old.status='FINALIZED' and new.status='FINALIZED' and old.snapshot->>'journalId' is null and s->>'journalId' is not null)) then raise exception 'Invalid payroll transition';end if;
  if not(old.status in('CALCULATED','RETURNED') and new.status='CALCULATED') and (s-'status'-'version'-'approvedBy'-'approvedEmployee'-'journalId'-'reason'-'recordedAt') is distinct from (old.snapshot-'status'-'version'-'approvedBy'-'approvedEmployee'-'journalId'-'reason'-'recordedAt') then raise exception 'Frozen payroll result';end if;
  if new.status<>'FINALIZED' and (s->>'approvedBy' is distinct from old.snapshot->>'approvedBy' or s->>'approvedEmployee' is distinct from old.snapshot->>'approvedEmployee') then raise exception 'Invalid payroll approval change';end if;
 end if;
 if new.status in('SUBMITTED','FINALIZED') and (net<=0 or jsonb_typeof(s->'exceptions') is distinct from 'array' or jsonb_array_length(s->'exceptions')<>0) then raise exception 'Blocking payroll exceptions';end if;
 if new.status='FINALIZED' and (coalesce(s->>'approvedEmployee','')='' or s->>'approvedEmployee'=s->>'preparedEmployee' or s->>'approvedEmployee'=new.employee_id or s->>'approvedBy'=s->>'preparedBy') then raise exception 'Independent payroll approval required';end if;
 return new;
end $$;
create trigger pay_run_integrity before insert or update on pay_run for each row execute function pay_run_guard();
-- Financial history and receipts are append-only. Ordinary grants are provisioned externally; no grant UI is claimed.
create trigger fin_ledger_immutable before update or delete on fin_ledger for each row execute function prevent_audit_change();
create trigger fin_period_immutable before update or delete on fin_period for each row execute function prevent_audit_change();
create trigger fin_audit_immutable before update or delete on fin_audit for each row execute function prevent_audit_change();
create trigger fin_command_immutable before update or delete on fin_command for each row execute function prevent_audit_change();
create trigger pay_audit_immutable before update or delete on pay_audit for each row execute function prevent_audit_change();
create trigger pay_command_immutable before update or delete on pay_command for each row execute function prevent_audit_change();
create trigger fin_journal_no_delete before delete on fin_journal for each row execute function prevent_audit_change();
create trigger pay_run_no_delete before delete on pay_run for each row execute function prevent_audit_change();
create trigger fin_ledger_no_truncate before truncate on fin_ledger for each statement execute function prevent_audit_change();
create trigger fin_period_no_truncate before truncate on fin_period for each statement execute function prevent_audit_change();
create trigger fin_journal_no_truncate before truncate on fin_journal for each statement execute function prevent_audit_change();
create trigger fin_audit_no_truncate before truncate on fin_audit for each statement execute function prevent_audit_change();
create trigger fin_command_no_truncate before truncate on fin_command for each statement execute function prevent_audit_change();
create trigger pay_run_no_truncate before truncate on pay_run for each statement execute function prevent_audit_change();
create trigger pay_audit_no_truncate before truncate on pay_audit for each statement execute function prevent_audit_change();
create trigger pay_command_no_truncate before truncate on pay_command for each statement execute function prevent_audit_change();
