-- Append-only withdrawal; an unreviewed request never changes employment/access.
create table hr_end_withdrawal (
 tenant varchar(80) not null,id varchar(40) not null,request_id varchar(40) not null,employee_id varchar(40) not null,
 actor varchar(120) not null,actor_employee_id varchar(40) not null,reason varchar(500) not null check(length(trim(reason))>0),
 input_version bigint not null check(input_version>=0),recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,id),unique(tenant,actor,idempotency_key),
 foreign key(tenant,request_id) references hr_employment_end(tenant,request_id),
 foreign key(tenant,actor_employee_id) references hr_employee(tenant,id),check(actor_employee_id<>employee_id)
);
create table hr_end_withdrawal_decision (
 tenant varchar(80) not null,withdrawal_id varchar(40) not null,
 status varchar(20) not null check(status in('APPROVED','REJECTED')),
 reviewer varchar(120) not null,reviewer_employee_id varchar(40) not null,
 comment varchar(500) not null check(length(trim(comment))>0),review_version bigint not null check(review_version>=0),
 reviewed_at timestamptz not null,review_correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,withdrawal_id),unique(tenant,reviewer,idempotency_key),
 foreign key(tenant,withdrawal_id) references hr_end_withdrawal(tenant,id),
 foreign key(tenant,reviewer_employee_id) references hr_employee(tenant,id)
);
create trigger immutable_end_withdrawal before update or delete on hr_end_withdrawal for each row execute function prevent_audit_change();
create trigger immutable_end_withdrawal_decision before update or delete on hr_end_withdrawal_decision for each row execute function prevent_audit_change();
create view hr_end_withdrawal_current as
 select w.tenant,w.id,w.request_id,w.employee_id,w.actor,w.actor_employee_id,w.reason,w.input_version,w.recorded_at,w.correlation_id,
 coalesce(d.status,'SUBMITTED')::varchar as status,d.reviewer,d.reviewer_employee_id,d.comment,d.review_version,d.reviewed_at,d.review_correlation_id
 from hr_end_withdrawal w left join hr_end_withdrawal_decision d on d.tenant=w.tenant and d.withdrawal_id=w.id;
create view hr_employment_end_current as
 select e.* from hr_employment_end e where not exists(select 1 from hr_end_withdrawal_current w where w.tenant=e.tenant and w.request_id=e.request_id and w.status='APPROVED');
create view hr_employment_end_handoff_current as
 select h.tenant,h.employee_id,h.target,h.inactive_date,h.recorded_at,h.correlation_id,
 case when exists(select 1 from hr_employment_end_current e where e.tenant=h.tenant and e.employee_id=h.employee_id) then h.status else 'SUPPRESSED' end::varchar as status
 from hr_employment_end_handoff h;
create or replace function hr_employment_status(t varchar,e varchar,original varchar,as_of_date date) returns varchar language sql stable as $$
 select case when exists(select 1 from hr_employment_end_current x where x.tenant=t and x.employee_id=e and x.inactive_date<=as_of_date) then 'Ended' else original end;
$$;
create function guard_end_withdrawal() returns trigger language plpgsql as $$
declare e hr_employment_end; w hr_end_withdrawal;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 if TG_TABLE_NAME='hr_end_withdrawal' then
  select * into e from hr_employment_end where tenant=new.tenant and request_id=new.request_id;
  if e.employee_id is distinct from new.employee_id or (new.recorded_at at time zone 'UTC')::date>=e.inactive_date then raise exception 'Pre-effective matching schedule required'; end if;
  if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.subject=new.actor and m.employee_id=new.actor_employee_id and m.role='HR') then raise exception 'HR requester required'; end if;
  if not exists(select 1 from hr_employee x where x.tenant=new.tenant and x.id=new.employee_id and x.version=new.input_version) then raise exception 'Employee snapshot mismatch'; end if;
  if (select count(*) from hr_end_withdrawal where tenant=new.tenant and request_id=new.request_id)>=20 or exists(select 1 from hr_end_withdrawal_current x where x.tenant=new.tenant and x.request_id=new.request_id and x.status in('SUBMITTED','APPROVED')) then raise exception 'Pending/approved withdrawal or attempt limit'; end if;
 else
  select * into w from hr_end_withdrawal where tenant=new.tenant and id=new.withdrawal_id;
  select * into e from hr_employment_end where tenant=w.tenant and request_id=w.request_id;
  if new.reviewer=w.actor or new.reviewer_employee_id in(w.actor_employee_id,w.employee_id) or new.reviewed_at<w.recorded_at then raise exception 'Independent reviewer/time required'; end if;
  if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.subject=new.reviewer and m.employee_id=new.reviewer_employee_id and m.role='HR') then raise exception 'HR reviewer required'; end if;
  if new.status='APPROVED' and ((new.reviewed_at at time zone 'UTC')::date>=e.inactive_date or new.review_version<>w.input_version) then raise exception 'Pre-effective unchanged snapshot required'; end if;
  if not exists(select 1 from hr_employee x where x.tenant=w.tenant and x.id=w.employee_id and x.version=new.review_version+case when new.status='APPROVED' then 1 else 0 end) then raise exception 'Review employee version mismatch'; end if;
 end if;
 return new;
end $$;
create trigger guard_end_withdrawal_insert before insert on hr_end_withdrawal for each row execute function guard_end_withdrawal();
create trigger guard_end_withdrawal_decision_insert before insert on hr_end_withdrawal_decision for each row execute function guard_end_withdrawal();
-- Keep historical schedules intact; only a current schedule closes coordination.
create or replace function guard_exit_lifecycle() returns trigger language plpgsql as $$
declare target_employee varchar;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 if TG_TABLE_NAME='hr_exit_task' then
  if exists(select 1 from hr_employment_end_current e where e.tenant=new.tenant and e.employee_id in(new.employee_id,new.assignee_id)) then raise exception 'Scheduled employment end prevents task assignment'; end if;
 else
  select employee_id into target_employee from hr_exit_task where tenant=new.tenant and id=new.task_id;
  if exists(select 1 from hr_employment_end_current e where e.tenant=new.tenant and (e.employee_id=target_employee or(new.action='REASSIGN' and e.employee_id=new.new_assignee_id))) then raise exception 'Scheduled employment end prevents task management'; end if;
 end if;
 return new;
end $$;
