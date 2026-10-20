-- Allow audited readiness/profile revisions and new nominees; preserve the original plan and position.
create or replace function tal_guard() returns trigger language plpgsql as $$
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
   when 'tal_succession' then oldstate='ACTIVE' and newstate='ACTIVE' and new.snapshot->>'position' is not distinct from old.snapshot->>'position'
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
