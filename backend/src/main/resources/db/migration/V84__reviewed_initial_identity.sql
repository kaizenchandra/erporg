-- Initial provider bindings are separate from employment activation and rehire/replacement.
create table hr_initial_identity_request(
 tenant varchar(80) not null,id varchar(40) not null,employee_id varchar(40) not null,
 prepared_by varchar(120) not null,command_key varchar(80) not null,snapshot jsonb not null,
 primary key(tenant,id),unique(tenant,prepared_by,command_key),
 foreign key(tenant,employee_id) references hr_employee(tenant,id),
 check(snapshot->>'id'=id and snapshot->>'employeeId'=employee_id and snapshot->>'preparedBy'=prepared_by and snapshot->>'idempotencyKey'=command_key),
 check(length(snapshot->>'issuer') between 1 and 500 and length(snapshot->>'federatedSubject') between 1 and 120 and length(btrim(snapshot->>'reason')) between 1 and 500 and length(snapshot->>'correlationId') between 1 and 80 and (snapshot->>'employeeVersion')::numeric>=0)
);
create index hr_initial_identity_employee on hr_initial_identity_request(tenant,employee_id);
create table hr_initial_identity_decision(
 tenant varchar(80) not null,request_id varchar(40) not null,snapshot jsonb not null,
 primary key(tenant,request_id),foreign key(tenant,request_id) references hr_initial_identity_request,
 check(snapshot->>'requestId'=request_id and snapshot->>'decision' in('APPROVE','REJECT') and length(btrim(snapshot->>'reason')) between 1 and 500 and length(snapshot->>'correlationId') between 1 and 80)
);
create function hr_initial_identity_guard() returns trigger language plpgsql as $$
declare r jsonb;e hr_employee%rowtype; m identity_membership%rowtype;
begin
 select snapshot into strict r from hr_initial_identity_request where tenant=new.tenant and id=new.request_id;
 if new.snapshot->>'actor'=r->>'preparedBy' or new.snapshot->>'actorEmployee'=r->>'preparedEmployee' or new.snapshot->>'actorEmployee'=r->>'employeeId' or not exists(select 1 from identity_membership where tenant=new.tenant and subject=new.snapshot->>'actor' and employee_id=new.snapshot->>'actorEmployee' and role='HR') then raise exception 'Independent HR identity review required';end if;
 if new.snapshot->>'decision'='APPROVE' then
  select * into strict e from hr_employee where tenant=new.tenant and id=r->>'employeeId';
  select * into strict m from identity_membership where subject=r->>'federatedSubject';
  if e.version<>(r->>'employeeVersion')::bigint or e.start_date<>(r->>'effectiveFrom')::date or e.status not in('Onboarding','Active') or m.tenant<>new.tenant or m.employee_id<>e.id or m.role<>'EMPLOYEE' or exists(select 1 from identity_membership where tenant=new.tenant and employee_id=e.id and subject<>m.subject) or exists(select 1 from hr_employment_episode where tenant=new.tenant and employee_id=e.id) or exists(select 1 from hr_local_access_grant where subject=m.subject) or exists(select 1 from hr_rehire_access_revocation where subject=m.subject) or not exists(select 1 from hr_hire_request where tenant=new.tenant and employee_id=e.id and status='APPROVED') then raise exception 'Invalid initial identity binding';end if;
 end if;
 return null;
end $$;
create constraint trigger hr_initial_identity_integrity after insert on hr_initial_identity_decision deferrable initially deferred for each row execute function hr_initial_identity_guard();
do $$ declare t text; begin
 foreach t in array array['hr_initial_identity_request','hr_initial_identity_decision'] loop
 execute format('create trigger %I before update or delete on %I for each row execute function prevent_audit_change()',t||'_immutable',t);
 execute format('create trigger %I before truncate on %I for each statement execute function prevent_audit_change()',t||'_no_truncate',t);
 end loop;
 execute format('alter function hr_initial_identity_guard() set search_path to %I,pg_catalog',current_schema());
end $$;
