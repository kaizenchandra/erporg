-- Payroll-owned, independently established baselines and effective talent awards.
create function pay_round_even_precision(v numeric,p integer) returns numeric language sql immutable strict as $$select pay_round_even(v*power(10::numeric,p-2))/power(10::numeric,p-2)$$;
create table pay_comp_setup(tenant varchar(80) not null,id varchar(40) not null,employee_id varchar(40) not null,actor varchar(120) not null,command_key varchar(80) not null,snapshot jsonb not null,correlation_id varchar(80) not null,primary key(tenant,id),unique(tenant,actor,command_key),check(snapshot->>'id'=id and snapshot->'input'->>'employeeId'=employee_id and snapshot->>'preparedBy'=actor and snapshot->>'status'='SUBMITTED'));
create index pay_comp_setup_employee on pay_comp_setup(tenant,employee_id);
create table pay_comp_setup_decision(tenant varchar(80) not null,setup_id varchar(40) not null,snapshot jsonb not null,command_key varchar(80) not null,correlation_id varchar(80) not null,primary key(tenant,setup_id),foreign key(tenant,setup_id) references pay_comp_setup,check(snapshot->>'id'=setup_id and snapshot->>'status' in('APPROVED','REJECTED')));
create table pay_comp_salary(tenant varchar(80) not null,id varchar(40) not null,employee_id varchar(40) not null,source_id varchar(40) not null,revision bigint not null check(revision>0),effective_date date not null,snapshot jsonb not null,bonus numeric(14,2) not null check(bonus>=0),actor varchar(120) not null,reason varchar(500) not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,primary key(tenant,id),unique(tenant,employee_id,revision),unique(tenant,employee_id,source_id),check(snapshot->>'id'=id and snapshot->>'employeeId'=employee_id and snapshot->>'sourceId'=source_id and (snapshot->>'revision')::bigint=revision and (snapshot->>'effectiveDate')::date=effective_date and snapshot->>'currency' in('USD','EUR','GBP','INR')),check((snapshot->>'annualSalary')::numeric between .01 and 999999999.99 and (snapshot->>'annualSalary')::numeric=round((snapshot->>'annualSalary')::numeric,2) and (snapshot->>'annualHours')::numeric between 1 and 8784 and (snapshot->>'annualHours')::numeric=round((snapshot->>'annualHours')::numeric,2) and length(btrim(reason))>0));
create index pay_comp_salary_effective on pay_comp_salary(tenant,employee_id,effective_date desc,revision desc);
create table pay_comp_run(tenant varchar(80) not null,run_id varchar(40) not null,employee_id varchar(40) not null,snapshot jsonb not null,primary key(tenant,run_id),foreign key(tenant,run_id) references pay_run deferrable initially deferred,check(snapshot->>'runId'=run_id and snapshot->>'employeeId'=employee_id and snapshot->>'ruleVersion'='DEMO-COMPENSATION-1;RATE-4DP;USD-2DP-HALF-EVEN;NO-STATUTORY-RULES'));
create table pay_comp_bonus_use(tenant varchar(80) not null,salary_id varchar(40) not null,run_id varchar(40) not null,primary key(tenant,salary_id),foreign key(tenant,salary_id) references pay_comp_salary,foreign key(tenant,run_id) references pay_comp_run);
create function pay_comp_salary_guard() returns trigger language plpgsql as $$
declare p jsonb; r jsonb; a jsonb; latest_revision bigint; prior jsonb;
begin
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/pay-compensation/'||new.tenant||'/'||new.employee_id,0));
 select coalesce(max(revision),0) into latest_revision from pay_comp_salary where tenant=new.tenant and employee_id=new.employee_id and revision<new.revision;
 if new.revision<>latest_revision+1 then raise exception 'Invalid compensation revision';end if;
 if new.revision>1 and exists(select 1 from pay_comp_run r cross join lateral jsonb_array_elements(r.snapshot->'days') d where r.tenant=new.tenant and r.employee_id=new.employee_id and d->'salary'->>'episodeStart'=new.snapshot->>'episodeStart' and (d->'salary'->>'revision')::bigint<new.revision and (d->>'date')::date>=new.effective_date) then raise exception 'Calculated compensation requires controlled payroll correction';end if;
 if new.revision=1 then
  select d.snapshot into p from pay_comp_setup_decision d where d.tenant=new.tenant and d.setup_id=new.source_id;
  if p is null or p->>'status'<>'APPROVED' or p->'input'->>'employeeId'<>new.employee_id or p->>'reviewedBy'<>new.actor or p->'input'->>'currency'<>new.snapshot->>'currency' or (p->'input'->>'annualSalary')::numeric<>(new.snapshot->>'annualSalary')::numeric or (p->'input'->>'annualHours')::numeric<>(new.snapshot->>'annualHours')::numeric or (p->'input'->>'effectiveDate')::date<>new.effective_date or p->>'episodeStart'<>new.snapshot->>'episodeStart' or new.bonus<>0 then raise exception 'Salary baseline requires exact approved source';end if;
 else
  select snapshot into r from pay_talent_intake where tenant=new.tenant and source_id=new.source_id;
  select value into a from jsonb_array_elements(r->'awards') where value->>'employeeId'=new.employee_id;
  select snapshot into prior from pay_comp_salary where tenant=new.tenant and employee_id=new.employee_id and revision=new.revision-1;
  if a is null or r->>'currency'<>new.snapshot->>'currency' or (a->>'proposedSalary')::numeric<>(new.snapshot->>'annualSalary')::numeric or (a->>'currentSalary')::numeric<>(prior->>'annualSalary')::numeric or (a->>'bonus')::numeric<>new.bonus or (a->>'effectiveDate')::date<>new.effective_date or new.effective_date<(prior->>'effectiveDate')::date or new.snapshot->>'annualHours'<>prior->>'annualHours' or new.snapshot->>'episodeStart'<>prior->>'episodeStart' then raise exception 'Compensation award requires exact approved intake and prior salary';end if;
 end if;
 return null;
end $$;
create constraint trigger pay_comp_salary_integrity after insert on pay_comp_salary deferrable initially deferred for each row execute function pay_comp_salary_guard();
create function pay_comp_setup_guard() returns trigger language plpgsql as $$
declare r jsonb;
begin
 select snapshot into strict r from pay_comp_setup where tenant=new.tenant and id=new.setup_id;
 if (new.snapshot-'status'-'reviewedBy'-'reviewedEmployee'-'reviewReason'-'recordedAt') is distinct from (r-'status'-'reviewedBy'-'reviewedEmployee'-'reviewReason'-'recordedAt') or new.snapshot->>'reviewedBy'=r->>'preparedBy' or new.snapshot->>'reviewedEmployee'=r->>'preparedEmployee' or new.snapshot->>'reviewedEmployee'=r->'input'->>'employeeId' or coalesce(btrim(new.snapshot->>'reviewReason'),'')='' or coalesce(new.snapshot->>'reviewedEmployee','')='' then raise exception 'Independent exact baseline review required';end if;
 if new.snapshot->>'status'='APPROVED' and not exists(select 1 from pay_comp_salary where tenant=new.tenant and source_id=new.setup_id and revision=1) then raise exception 'Baseline approval requires salary creation';end if;
 return null;
end $$;
create constraint trigger pay_comp_setup_integrity after insert on pay_comp_setup_decision deferrable initially deferred for each row execute function pay_comp_setup_guard();
create function pay_comp_run_guard() returns trigger language plpgsql as $$
declare p jsonb;d jsonb;s jsonb;b jsonb;base numeric=0;bonus numeric=0;minutes integer=0;ids text[]='{}';dates date[]='{}';
begin
 select snapshot into strict p from pay_run where tenant=new.tenant and id=new.run_id;
 if p->>'employeeId'<>new.employee_id or p->>'ruleVersion'<>new.snapshot->>'ruleVersion' or p->'source' is distinct from new.snapshot->'source' or p->>'entity'<>new.snapshot->>'entity' or p->>'currency'<>new.snapshot->>'currency' or coalesce(new.snapshot->>'actor','')='' or coalesce(btrim(new.snapshot->>'reason'),'')='' then raise exception 'Invalid compensation payroll provenance';end if;
 if jsonb_array_length(new.snapshot->'days') not between 1 and 7 then raise exception 'Compensation days required';end if;
 for d in select value from jsonb_array_elements(new.snapshot->'days') loop
  select snapshot into strict s from pay_comp_salary where tenant=new.tenant and id=d->'salary'->>'id' and employee_id=new.employee_id;
  if d->'salary' is distinct from s or (d->>'date')::date=any(dates) or not exists(select 1 from jsonb_array_elements(p->'source'->'days') t where t->>'date'=d->>'date' and t->>'minutes'=d->>'minutes') or (s->>'effectiveDate')::date>(d->>'date')::date or s->>'episodeStart'<>p->>'episodeStart' or exists(select 1 from pay_comp_salary later where later.tenant=new.tenant and later.employee_id=new.employee_id and later.revision>(s->>'revision')::bigint and later.recorded_at<= (new.snapshot->>'recordedAt')::timestamptz and later.effective_date<=(d->>'date')::date) or (d->>'hourlyRate')::numeric<>pay_round_even_precision((s->>'annualSalary')::numeric/(s->>'annualHours')::numeric,4) then raise exception 'Invalid authoritative salary day';end if;
  dates:=array_append(dates,(d->>'date')::date);minutes:=minutes+(d->>'minutes')::integer;base:=base+pay_round_even_precision((d->>'minutes')::numeric*(d->>'hourlyRate')::numeric/60,12);
 end loop;
 for b in select value from jsonb_array_elements(new.snapshot->'bonuses') loop
  if b->>'id'=any(ids) or not exists(select 1 from pay_comp_salary s join pay_comp_bonus_use u on u.tenant=s.tenant and u.salary_id=s.id where s.tenant=new.tenant and s.id=b->>'id' and s.employee_id=new.employee_id and s.source_id=b->>'sourceId' and s.bonus=(b->>'amount')::numeric and s.effective_date=(b->>'effectiveDate')::date and s.effective_date<=(p->'source'->>'week')::date+6 and u.run_id=new.run_id) then raise exception 'Invalid or duplicate bonus reservation';end if;
  ids:=array_append(ids,b->>'id');bonus:=bonus+(b->>'amount')::numeric;
 end loop;
 if (select count(*) from pay_comp_bonus_use where tenant=new.tenant and run_id=new.run_id)<>cardinality(ids) or minutes<>(p->'source'->>'totalMinutes')::integer or pay_round_even(base)<>(new.snapshot->>'baseGross')::numeric or bonus<>(new.snapshot->>'bonus')::numeric or pay_round_even(base)+bonus<>(new.snapshot->>'gross')::numeric or (new.snapshot->>'gross')::numeric<>(p->>'gross')::numeric then raise exception 'Compensation payroll control total mismatch';end if;
 return null;
end $$;
create constraint trigger pay_comp_run_integrity after insert on pay_comp_run deferrable initially deferred for each row execute function pay_comp_run_guard();
do $$ declare t text; f text; begin
 foreach t in array array['pay_comp_setup','pay_comp_setup_decision','pay_comp_salary','pay_comp_run','pay_comp_bonus_use'] loop
 execute format('create trigger %I before update or delete on %I for each row execute function prevent_audit_change()',t||'_immutable',t);
 execute format('create trigger %I before truncate on %I for each statement execute function prevent_audit_change()',t||'_no_truncate',t);
 end loop;
 foreach f in array array['pay_comp_salary_guard','pay_comp_setup_guard','pay_comp_run_guard'] loop
 execute format('alter function %I() set search_path to %I,pg_catalog',f,current_schema());end loop;
end $$;

-- Preserve the prior rule and lifecycle guards; add an explicitly separate snapshot-backed rule.
create or replace function pay_run_guard() returns trigger language plpgsql as $$
declare s jsonb;gross numeric;deduction numeric;net numeric;minutes numeric;rate numeric;comp jsonb;
begin
 s:=new.snapshot;
 if s->>'id' is distinct from new.id or s->>'entity' is distinct from new.entity or s->>'employeeId' is distinct from new.employee_id or s->'source'->>'id' is distinct from new.source_id or s->>'status' is distinct from new.status or (s->>'version')::numeric is distinct from new.version or s->>'currency' is distinct from 'USD' or s->>'ruleVersion' not in('DEMO-HOURLY-1;USD-2DP-HALF-EVEN;NO-STATUTORY-RULES','DEMO-COMPENSATION-1;RATE-4DP;USD-2DP-HALF-EVEN;NO-STATUTORY-RULES') or s->>'paymentStatus' is distinct from 'NOT_CONFIGURED' then raise exception 'Invalid demonstration payroll snapshot';end if;
 gross:=(s->>'gross')::numeric;deduction:=(s->>'deduction')::numeric;net:=(s->>'net')::numeric;minutes:=(s->'source'->>'totalMinutes')::numeric;rate:=(s->>'hourlyRate')::numeric;
 if minutes is null or minutes<>trunc(minutes) or minutes not between 1 and 10080 or rate is null or rate<=0 or rate>1000000 or rate<>round(rate,4) or gross is null or deduction is null or net is null or deduction<0 or deduction>999999999999.99 or deduction<>round(deduction,2) or (s->>'ruleVersion'='DEMO-HOURLY-1;USD-2DP-HALF-EVEN;NO-STATUTORY-RULES' and gross<>pay_round_even(minutes*rate/60)) or gross-deduction<>net then raise exception 'Invalid payroll control totals';end if;
 if s->>'ruleVersion'='DEMO-COMPENSATION-1;RATE-4DP;USD-2DP-HALF-EVEN;NO-STATUTORY-RULES' then
 select snapshot into comp from pay_comp_run where tenant=new.tenant and run_id=new.id;
 if comp is null or (comp->>'gross')::numeric<>gross or (comp->>'hourlyRate')::numeric<>rate then raise exception 'Authoritative payroll requires immutable compensation calculation';end if;
 elsif exists(select 1 from pay_comp_run where tenant=new.tenant and run_id=new.id) then raise exception 'Compensation source cannot switch to entered hourly mode';end if;
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

do $$ begin execute format('alter function pay_run_guard() set search_path to %I,pg_catalog',current_schema());end $$;

do $$ begin execute format('alter function pay_round_even_precision(numeric,integer) set search_path to %I,pg_catalog',current_schema());end $$;
