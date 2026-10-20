-- Talent owns these aggregate snapshots, receipt journal and append-only audit.
-- A tenant-scoped advisory lock bounds application workflow transactions.
create table tal_requisition(tenant varchar(80) not null,id varchar(40) not null,snapshot jsonb not null,primary key(tenant,id),check(snapshot->'meta'->>'status' in ('DRAFT','SUBMITTED','OPEN','RETURNED','CLOSED')),check(jsonb_typeof(snapshot->'candidates')='array' and jsonb_array_length(snapshot->'candidates')<=50));
create table tal_onboarding(tenant varchar(80) not null,id varchar(40) not null,snapshot jsonb not null,primary key(tenant,id),check(snapshot->'meta'->>'status' in ('OPEN','COMPLETED')),check(jsonb_typeof(snapshot->'tasks')='array' and jsonb_array_length(snapshot->'tasks')<=30));
create table tal_review(tenant varchar(80) not null,id varchar(40) not null,snapshot jsonb not null,primary key(tenant,id),check(snapshot->'meta'->>'status' in ('OPEN','SUBMITTED','RETURNED','COMPLETED')),check((snapshot->>'minimumRating')::int>=0 and (snapshot->>'maximumRating')::int<=10 and (snapshot->>'minimumRating')::int<(snapshot->>'maximumRating')::int),check((snapshot->>'through')::date>=(snapshot->>'from')::date));
create table tal_course(tenant varchar(80) not null,id varchar(40) not null,snapshot jsonb not null,primary key(tenant,id),check(snapshot->'meta'->>'status'='PUBLISHED'),check(snapshot->>'role' in ('ALL','HR','MANAGER','EMPLOYEE')),check((snapshot->>'validDays')::int between 0 and 3650));
create table tal_enrollment(tenant varchar(80) not null,id varchar(40) not null,snapshot jsonb not null,primary key(tenant,id),check(snapshot->'meta'->>'status' in ('ASSIGNED','COMPLETED')));
create unique index tal_enrollment_once on tal_enrollment(tenant,(snapshot->>'courseId'),(snapshot->>'employeeId'));
create table tal_compensation(tenant varchar(80) not null,id varchar(40) not null,snapshot jsonb not null,primary key(tenant,id),check(snapshot->'meta'->>'status' in ('DRAFT','SUBMITTED','RETURNED','APPROVED')),check(snapshot->>'currency' in ('USD','EUR','GBP','INR')),check((snapshot->>'total')::numeric>=0 and (snapshot->>'budget')::numeric>=(snapshot->>'total')::numeric),check(jsonb_typeof(snapshot->'proposals')='array' and jsonb_array_length(snapshot->'proposals') between 1 and 50));
create table tal_succession(tenant varchar(80) not null,id varchar(40) not null,snapshot jsonb not null,primary key(tenant,id),check(snapshot->'meta'->>'status'='ACTIVE'),check(jsonb_typeof(snapshot->'nominees')='array' and jsonb_array_length(snapshot->'nominees') between 1 and 30));
create table tal_scenario(tenant varchar(80) not null,id varchar(40) not null,snapshot jsonb not null,primary key(tenant,id),check(snapshot->'meta'->>'status'='SAVED'),check(snapshot->>'currency' in ('USD','EUR','GBP','INR')),check((snapshot->>'proposedCost')::numeric-(snapshot->>'baselineCost')::numeric=(snapshot->>'variance')::numeric),check((snapshot->>'baselineHeadcount')::int=jsonb_array_length(snapshot->'workerSnapshot')));
create table tal_command(tenant varchar(80) not null,subject varchar(120) not null,command_key varchar(80) not null,fingerprint varchar(64) not null,result jsonb not null,primary key(tenant,subject,command_key));
create table tal_audit(tenant varchar(80) not null,id varchar(40) not null,kind varchar(30) not null,record_id varchar(40) not null,snapshot jsonb not null,primary key(tenant,id),check(snapshot->>'id'=id and snapshot->>'recordId'=record_id and snapshot->>'kind'=kind),check(length(snapshot->>'reason') between 1 and 1000),check(snapshot ?& array['actor','employeeId','before','after','recordedAt','correlationId']));
create index tal_audit_record on tal_audit(tenant,kind,record_id,(snapshot->>'recordedAt'),id);
create index tal_review_worker on tal_review(tenant,(snapshot->>'employeeId'));
create index tal_onboarding_worker on tal_onboarding(tenant,(snapshot->>'employeeId'));
create index tal_enrollment_worker on tal_enrollment(tenant,(snapshot->>'employeeId'));
create function tal_immutable() returns trigger language plpgsql as $$begin raise exception 'Talent history and command receipts cannot be changed or deleted'; end$$;
create function tal_guard() returns trigger language plpgsql as $$
declare oldstate text; newstate text; allowed boolean; total numeric; item jsonb;
begin
 if tg_op='DELETE' then raise exception 'Talent records require retained history'; end if;
 if not (new.snapshot ? 'meta') or not (new.snapshot->'meta' ?& array['id','version','status','preparedBy','preparedEmployee','recordedAt']) or new.snapshot->'meta'->>'id' is distinct from new.id then raise exception 'Invalid talent identity'; end if;
 if jsonb_typeof(new.snapshot->'meta'->'version')<>'number' or (new.snapshot->'meta'->>'version')::numeric<>trunc((new.snapshot->'meta'->>'version')::numeric) then raise exception 'Exact revision required'; end if;
 newstate=new.snapshot->'meta'->>'status';
 if tg_op='INSERT' then
  if (new.snapshot->'meta'->>'version')::bigint<>0 then raise exception 'Initial revision must be zero'; end if;
  allowed=case tg_table_name when 'tal_requisition' then newstate='DRAFT' when 'tal_onboarding' then newstate='OPEN' when 'tal_review' then newstate='OPEN' when 'tal_course' then newstate='PUBLISHED' when 'tal_enrollment' then newstate='ASSIGNED' when 'tal_compensation' then newstate='DRAFT' when 'tal_succession' then newstate='ACTIVE' when 'tal_scenario' then newstate='SAVED' else false end;
 else
  if new.tenant<>old.tenant or new.id<>old.id or (new.snapshot->'meta'->>'version')::bigint<>(old.snapshot->'meta'->>'version')::bigint+1 or new.snapshot->'meta'->>'preparedBy' is distinct from old.snapshot->'meta'->>'preparedBy' or new.snapshot->'meta'->>'preparedEmployee' is distinct from old.snapshot->'meta'->>'preparedEmployee' then raise exception 'Immutable origin and next exact revision required'; end if;
  oldstate=old.snapshot->'meta'->>'status';
  allowed=case tg_table_name
   when 'tal_requisition' then (oldstate in ('DRAFT','RETURNED') and newstate='SUBMITTED') or (oldstate='SUBMITTED' and newstate in ('OPEN','RETURNED')) or (oldstate='OPEN' and newstate in ('OPEN','CLOSED'))
   when 'tal_onboarding' then oldstate='OPEN' and newstate in ('OPEN','COMPLETED')
   when 'tal_review' then (oldstate in ('OPEN','RETURNED') and newstate in (oldstate,'SUBMITTED')) or (oldstate='SUBMITTED' and newstate in ('RETURNED','COMPLETED'))
   when 'tal_enrollment' then oldstate='ASSIGNED' and newstate='COMPLETED'
   when 'tal_compensation' then (oldstate in ('DRAFT','RETURNED') and newstate='SUBMITTED') or (oldstate='SUBMITTED' and newstate in ('RETURNED','APPROVED'))
   else false end;
  if tg_table_name='tal_review' and (new.snapshot->>'employeeId' is distinct from old.snapshot->>'employeeId' or new.snapshot->>'minimumRating' is distinct from old.snapshot->>'minimumRating' or new.snapshot->>'maximumRating' is distinct from old.snapshot->>'maximumRating' or new.snapshot->>'from' is distinct from old.snapshot->>'from' or new.snapshot->>'through' is distinct from old.snapshot->>'through') then raise exception 'Review owner and cycle configuration are frozen'; end if;
  if tg_table_name='tal_compensation' and (new.snapshot-'meta'-'payrollIntakeId') is distinct from (old.snapshot-'meta'-'payrollIntakeId') then raise exception 'Submitted compensation inputs are immutable'; end if;
 end if;
 if not allowed then raise exception 'Forbidden talent workflow transition'; end if;
 if tg_table_name='tal_review' and newstate='COMPLETED' and (not (new.snapshot ? 'rating') or new.snapshot->>'rating' is null or (new.snapshot->>'rating')::int not between (new.snapshot->>'minimumRating')::int and (new.snapshot->>'maximumRating')::int) then raise exception 'Rating outside configured cycle'; end if;
 if tg_table_name='tal_compensation' then
  total=0;
  for item in select value from jsonb_array_elements(new.snapshot->'proposals') loop
   if (item->>'currentSalary')::numeric<0 or (item->>'proposedSalary')::numeric<(item->>'currentSalary')::numeric or (item->>'bonus')::numeric<0 then raise exception 'Invalid compensation proposal'; end if;
   total=total+(item->>'proposedSalary')::numeric-(item->>'currentSalary')::numeric+(item->>'bonus')::numeric;
  end loop;
  if total<>(new.snapshot->>'total')::numeric or total>(new.snapshot->>'budget')::numeric then raise exception 'Compensation budget does not reconcile'; end if;
  if newstate='APPROVED' and coalesce(new.snapshot->>'payrollIntakeId','')='' then raise exception 'Approved compensation needs durable payroll receipt'; end if;
 end if;
 if tg_table_name='tal_onboarding' and newstate='COMPLETED' and (coalesce(new.snapshot->>'joiningForm','')='' or jsonb_array_length(new.snapshot->'tasks')=0 or exists(select 1 from jsonb_array_elements(new.snapshot->'tasks') t where (t->>'complete')::boolean is distinct from true)) then raise exception 'Onboarding dependencies incomplete'; end if;
 return new;
end$$;
create function tal_audit_required() returns trigger language plpgsql as $$begin
 if not exists(select 1 from tal_audit a where a.tenant=new.tenant and a.record_id=new.id and (a.snapshot->>'after')::jsonb=new.snapshot and (case when tg_op='INSERT' then a.snapshot->>'before'='' else (a.snapshot->>'before')::jsonb=old.snapshot end)) then raise exception 'Talent mutation requires its exact audit snapshot'; end if;
 return null;
end$$;
do $$declare t text; begin
 foreach t in array array['tal_requisition','tal_onboarding','tal_review','tal_course','tal_enrollment','tal_compensation','tal_succession','tal_scenario'] loop
 execute format('create trigger talent_guard before insert or update or delete on %I for each row execute function tal_guard()',t);
 execute format('create constraint trigger talent_audited after insert or update on %I deferrable initially deferred for each row execute function tal_audit_required()',t);
 execute format('create trigger talent_no_truncate before truncate on %I execute function tal_immutable()',t);
 end loop;
 foreach t in array array['tal_command','tal_audit'] loop
 execute format('create trigger talent_history_immutable before update or delete or truncate on %I for each statement execute function tal_immutable()',t);
 end loop;
 execute format('alter function tal_audit_required() set search_path = %I,pg_catalog',current_schema());
end$$;

create unique index tal_onboarding_hire_once on tal_onboarding(tenant,(snapshot->>'hireId'));
