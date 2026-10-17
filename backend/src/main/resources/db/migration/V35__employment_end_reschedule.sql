create table hr_end_reschedule (
 tenant varchar(80) not null,id varchar(40) not null,request_id varchar(40) not null,employee_id varchar(40) not null,
 last_working_date date not null,prior_inactive_date date,old_status varchar(20) not null check(old_status in('Active','On leave')),
 actor varchar(120) not null,actor_employee_id varchar(40) not null,reason varchar(500) not null check(length(trim(reason))>0),
 input_version bigint not null check(input_version>=0),recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,id),unique(tenant,actor,idempotency_key),foreign key(tenant,request_id) references hr_employment_end(tenant,request_id),
 foreign key(tenant,actor_employee_id) references hr_employee(tenant,id),check(actor_employee_id<>employee_id)
);
create table hr_end_reschedule_decision (
 tenant varchar(80) not null,reschedule_id varchar(40) not null,status varchar(20) not null check(status in('APPROVED','REJECTED')),
 reviewer varchar(120) not null,reviewer_employee_id varchar(40) not null,comment varchar(500) not null check(length(trim(comment))>0),
 review_version bigint not null check(review_version>=0),reviewed_at timestamptz not null,review_correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,reschedule_id),unique(tenant,reviewer,idempotency_key),foreign key(tenant,reschedule_id) references hr_end_reschedule(tenant,id),
 foreign key(tenant,reviewer_employee_id) references hr_employee(tenant,id)
);
create table hr_end_reschedule_handoff (
 tenant varchar(80) not null,reschedule_id varchar(40) not null,target varchar(30) not null check(target in('FINAL_PAY','EXTERNAL_IDENTITY')),
 inactive_date date not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,status varchar(20) not null check(status='PENDING'),
 primary key(tenant,reschedule_id,target),foreign key(tenant,reschedule_id) references hr_end_reschedule_decision(tenant,reschedule_id)
);
create trigger immutable_end_reschedule before update or delete on hr_end_reschedule for each row execute function prevent_audit_change();
create trigger immutable_end_reschedule_decision before update or delete on hr_end_reschedule_decision for each row execute function prevent_audit_change();
create trigger immutable_end_reschedule_handoff before update or delete on hr_end_reschedule_handoff for each row execute function prevent_audit_change();
create view hr_end_reschedule_current as
 select r.tenant,r.id,r.request_id,r.employee_id,r.last_working_date,r.prior_inactive_date,r.old_status,r.actor,r.actor_employee_id,r.reason,r.input_version,r.recorded_at,r.correlation_id,
 coalesce(d.status,'SUBMITTED')::varchar as status,d.reviewer,d.reviewer_employee_id,d.comment,d.review_version,d.reviewed_at,d.review_correlation_id
 from hr_end_reschedule r left join hr_end_reschedule_decision d on d.tenant=r.tenant and d.reschedule_id=r.id;
-- Atomically replace derived views only, in Flyway's migration transaction; never drop history.
drop view hr_employment_end_handoff_current;
drop view hr_employment_end_current;
create view hr_employment_end_current as
 select e.* from hr_employment_end e where not exists(select 1 from hr_end_withdrawal_current w where w.tenant=e.tenant and w.request_id=e.request_id and w.status='APPROVED')
 and not exists(select 1 from hr_end_reschedule_current r where r.tenant=e.tenant and r.request_id=e.request_id and r.status='APPROVED')
 union all
 select r.tenant,r.request_id,r.employee_id,r.last_working_date,r.last_working_date+1,r.reviewer,r.reviewer_employee_id,r.comment,r.review_version,r.review_version+1,r.old_status,'Ended'::varchar,r.reviewed_at,r.review_correlation_id,d.idempotency_key
 from hr_end_reschedule_current r join hr_end_reschedule_decision d on d.tenant=r.tenant and d.reschedule_id=r.id where r.status='APPROVED';
create view hr_employment_end_handoff_current as
 select h.tenant,h.employee_id,h.target,h.inactive_date,h.recorded_at,h.correlation_id,
 case when exists(select 1 from hr_employment_end_current e where e.tenant=h.tenant and e.employee_id=h.employee_id and e.recorded_at=h.recorded_at and e.correlation_id=h.correlation_id) then h.status else 'SUPPRESSED' end::varchar as status
 from hr_employment_end_handoff h
 union all
 select h.tenant,r.employee_id,h.target,h.inactive_date,h.recorded_at,h.correlation_id,h.status from hr_end_reschedule_handoff h join hr_end_reschedule r on r.tenant=h.tenant and r.id=h.reschedule_id;
create function guard_end_reschedule() returns trigger language plpgsql as $$
declare e hr_employment_end; r hr_end_reschedule; current_date_boundary date; moment date;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 if TG_TABLE_NAME='hr_end_reschedule' then
  select * into e from hr_employment_end where tenant=new.tenant and request_id=new.request_id;
  select inactive_date into current_date_boundary from hr_employment_end_current where tenant=new.tenant and request_id=new.request_id;
  moment=(new.recorded_at at time zone 'UTC')::date;
  if e.employee_id is distinct from new.employee_id or new.prior_inactive_date is distinct from current_date_boundary or current_date_boundary<=moment or new.last_working_date<moment or new.last_working_date>(moment+interval '1 year')::date or new.last_working_date+1=current_date_boundary then raise exception 'Matching pre-effective replacement/date required'; end if;
  if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.subject=new.actor and m.employee_id=new.actor_employee_id and m.role='HR') then raise exception 'HR requester required'; end if;
  if not exists(select 1 from hr_employee x where x.tenant=new.tenant and x.id=new.employee_id and x.version=new.input_version and x.status=new.old_status and x.start_date<new.last_working_date+1) then raise exception 'Employee snapshot mismatch'; end if;
  if (select count(*) from hr_end_reschedule where tenant=new.tenant and request_id=new.request_id)>=20 or exists(select 1 from hr_end_reschedule_current x where x.tenant=new.tenant and x.request_id=new.request_id and x.status in('SUBMITTED','APPROVED')) or exists(select 1 from hr_end_withdrawal_current w where w.tenant=new.tenant and w.request_id=new.request_id and w.status='SUBMITTED') then raise exception 'Resolve review or attempt limit reached'; end if;
 else
  select * into r from hr_end_reschedule where tenant=new.tenant and id=new.reschedule_id;
  moment=(new.reviewed_at at time zone 'UTC')::date;
  if new.reviewer=r.actor or new.reviewer_employee_id in(r.actor_employee_id,r.employee_id) or new.reviewed_at<r.recorded_at then raise exception 'Independent reviewer/time required'; end if;
  if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.subject=new.reviewer and m.employee_id=new.reviewer_employee_id and m.role='HR') then raise exception 'HR reviewer required'; end if;
  if not exists(select 1 from hr_employee x where x.tenant=r.tenant and x.id=r.employee_id and x.version=new.review_version+case when new.status='APPROVED' then 1 else 0 end) then raise exception 'Review employee version mismatch'; end if;
  if new.status='APPROVED' then
   select inactive_date into current_date_boundary from hr_employment_end_current where tenant=r.tenant and request_id=r.request_id;
   if r.prior_inactive_date is distinct from current_date_boundary or current_date_boundary<=moment or r.last_working_date<moment or r.last_working_date>(moment+interval '1 year')::date or new.review_version<>r.input_version then raise exception 'Pre-effective unchanged snapshot required'; end if;
   if exists(select 1 from hr_exit_task_current t where t.tenant=r.tenant and t.status='OPEN' and (t.request_id=r.request_id or t.assignee_id=r.employee_id)) then raise exception 'Resolve open tasks'; end if;
  end if;
 end if;
 return new;
end $$;
create trigger guard_end_reschedule_insert before insert on hr_end_reschedule for each row execute function guard_end_reschedule();
create trigger guard_end_reschedule_decision_insert before insert on hr_end_reschedule_decision for each row execute function guard_end_reschedule();
create function guard_original_withdrawal_after_reschedule() returns trigger language plpgsql as $$
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 if exists(select 1 from hr_end_reschedule_current r where r.tenant=new.tenant and r.request_id=new.request_id and r.status in('SUBMITTED','APPROVED')) then raise exception 'Replacement review/current schedule prevents original withdrawal'; end if;
 return new;
end $$;
create trigger aaa_end_withdrawal_reschedule before insert on hr_end_withdrawal for each row execute function guard_original_withdrawal_after_reschedule();
create function reconcile_end_reschedule_handoff() returns trigger language plpgsql as $$
declare r hr_end_reschedule; d hr_end_reschedule_decision;
begin
 select * into r from hr_end_reschedule where tenant=new.tenant and id=new.reschedule_id;
 select * into d from hr_end_reschedule_decision where tenant=new.tenant and reschedule_id=new.reschedule_id;
 if d.status='APPROVED' then
  if (select count(*) from hr_end_reschedule_handoff h where h.tenant=r.tenant and h.reschedule_id=r.id and h.inactive_date=r.last_working_date+1 and h.recorded_at=d.reviewed_at and h.correlation_id=d.review_correlation_id)<>2 then raise exception 'Replacement handoffs mismatch'; end if;
 elsif exists(select 1 from hr_end_reschedule_handoff h where h.tenant=r.tenant and h.reschedule_id=r.id) then raise exception 'Rejected request cannot have handoffs'; end if;
 return null;
end $$;
create constraint trigger end_reschedule_reconciled after insert on hr_end_reschedule_decision deferrable initially deferred for each row execute function reconcile_end_reschedule_handoff();
create constraint trigger end_reschedule_handoff_reconciled after insert on hr_end_reschedule_handoff deferrable initially deferred for each row execute function reconcile_end_reschedule_handoff();
