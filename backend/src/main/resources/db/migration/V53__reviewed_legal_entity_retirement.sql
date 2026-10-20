-- Retains immutable declared references; retirement has no employee/payroll effect.
create table hr_legal_entity_retirement_request(
 tenant varchar(40) not null, id varchar(36) not null, code varchar(30) not null,
 ordinal integer not null check(ordinal between 1 and 20),
 source_version bigint not null check(source_version=(ordinal-1)*2),
 effective_date date not null,
 reason varchar(500) not null check(length(trim(reason)) between 1 and 500 and reason=trim(reason) and reason !~ '[[:cntrl:]]'),
 requested_by varchar(120) not null check(length(trim(requested_by))>0),
 requester_employee_id varchar(40) not null,
 recorded_at timestamptz not null,
 correlation_id varchar(100) not null check(length(trim(correlation_id))>0),
 idempotency_key varchar(80) not null check(idempotency_key ~ '^[a-zA-Z0-9-]{8,80}$'),
 primary key(tenant,id),unique(tenant,code,id),unique(tenant,code,ordinal),unique(tenant,requested_by,idempotency_key),
 foreign key(tenant,code) references hr_legal_entity(tenant,code),
 foreign key(tenant,requester_employee_id) references hr_employee(tenant,id)
);
create table hr_legal_entity_retirement_decision(
 tenant varchar(40) not null, request_id varchar(36) not null, code varchar(30) not null,
 decision varchar(8) not null check(decision in('APPROVED','REJECTED')),
 comment varchar(500) not null check(length(trim(comment)) between 1 and 500 and comment=trim(comment) and comment !~ '[[:cntrl:]]'),
 actor varchar(120) not null check(length(trim(actor))>0),actor_employee_id varchar(40) not null,
 recorded_at timestamptz not null,correlation_id varchar(100) not null check(length(trim(correlation_id))>0),
 idempotency_key varchar(80) not null check(idempotency_key ~ '^[a-zA-Z0-9-]{8,80}$'),
 primary key(tenant,request_id),unique(tenant,actor,idempotency_key),
 foreign key(tenant,code,request_id) references hr_legal_entity_retirement_request(tenant,code,id),
 foreign key(tenant,actor_employee_id) references hr_employee(tenant,id)
);
create unique index one_approved_legal_entity_retirement on hr_legal_entity_retirement_decision(tenant,code) where decision='APPROVED';
create trigger immutable_legal_entity_retirement_request before update or delete on hr_legal_entity_retirement_request for each row execute function prevent_audit_change();
create trigger immutable_legal_entity_retirement_request_truncate before truncate on hr_legal_entity_retirement_request for each statement execute function prevent_audit_change();
create trigger immutable_legal_entity_retirement_decision before update or delete on hr_legal_entity_retirement_decision for each row execute function prevent_audit_change();
create trigger immutable_legal_entity_retirement_decision_truncate before truncate on hr_legal_entity_retirement_decision for each statement execute function prevent_audit_change();
create function reconcile_legal_entity_retirement() returns trigger language plpgsql as $$
begin
 if exists(
  select 1 from hr_legal_entity_retirement_request r
  join hr_legal_entity e on e.tenant=r.tenant and e.code=r.code
  left join hr_legal_entity_retirement_request previous on previous.tenant=r.tenant and previous.code=r.code and previous.ordinal=r.ordinal-1
  left join hr_legal_entity_retirement_decision prior_decision on prior_decision.tenant=previous.tenant and prior_decision.request_id=previous.id
  left join hr_legal_entity_retirement_decision d on d.tenant=r.tenant and d.request_id=r.id
  where r.tenant=new.tenant and r.code=new.code and (
   r.effective_date<e.effective_from or r.recorded_at<e.recorded_at
   or r.effective_date<(r.recorded_at at time zone 'UTC')::date
   or r.effective_date>((r.recorded_at at time zone 'UTC')::date+interval '1 year')::date
   or (r.ordinal>1 and (previous.id is null or prior_decision.decision is distinct from 'REJECTED' or r.recorded_at<prior_decision.recorded_at))
   or (d.request_id is not null and (d.actor=r.requested_by or d.actor_employee_id=r.requester_employee_id or d.recorded_at<r.recorded_at or(d.decision='APPROVED' and r.effective_date<(d.recorded_at at time zone 'UTC')::date)))
  )
 ) then raise exception 'Legal-entity retirement history is inconsistent';end if;
 return null;
end $$;
create constraint trigger reconcile_legal_entity_retirement_request after insert on hr_legal_entity_retirement_request deferrable initially deferred for each row execute function reconcile_legal_entity_retirement();
create constraint trigger reconcile_legal_entity_retirement_decision after insert on hr_legal_entity_retirement_decision deferrable initially deferred for each row execute function reconcile_legal_entity_retirement();
create view hr_legal_entity_retirement_current as
 select r.tenant,r.code,r.id as request_id,r.effective_date as retirement_date,d.recorded_at as approved_at
 from hr_legal_entity_retirement_request r join hr_legal_entity_retirement_decision d on d.tenant=r.tenant and d.request_id=r.id and d.decision='APPROVED';
