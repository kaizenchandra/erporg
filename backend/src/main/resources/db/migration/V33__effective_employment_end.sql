create table hr_employment_end (
 tenant varchar(80) not null,request_id varchar(40) not null,employee_id varchar(40) not null,
 last_working_date date not null,inactive_date date not null check(inactive_date=last_working_date+1),
 actor varchar(120) not null,actor_employee_id varchar(40) not null,reason varchar(500) not null check(length(trim(reason))>0),
 input_version bigint not null check(input_version>=0),new_version bigint not null check(new_version=input_version+1),
 old_status varchar(20) not null check(old_status in('Active','On leave')),new_status varchar(20) not null check(new_status='Ended'),
 recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,employee_id),unique(tenant,request_id),unique(tenant,actor,idempotency_key),
 foreign key(tenant,request_id,employee_id) references hr_resignation(tenant,id,employee_id),foreign key(tenant,actor_employee_id) references hr_employee(tenant,id),
 check(actor_employee_id<>employee_id)
);
create trigger immutable_employment_end before update or delete on hr_employment_end for each row execute function prevent_audit_change();
create table hr_employment_end_handoff (
 tenant varchar(80) not null,employee_id varchar(40) not null,target varchar(30) not null check(target in('FINAL_PAY','EXTERNAL_IDENTITY')),
 inactive_date date not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,status varchar(20) not null check(status='PENDING'),
 primary key(tenant,employee_id,target),foreign key(tenant,employee_id) references hr_employment_end(tenant,employee_id)
);
create trigger immutable_employment_end_handoff before update or delete on hr_employment_end_handoff for each row execute function prevent_audit_change();
create function hr_employment_status(t varchar,e varchar,original varchar,as_of_date date) returns varchar language sql stable as $$
 select case when exists(select 1 from hr_employment_end x where x.tenant=t and x.employee_id=e and x.inactive_date<=as_of_date) then 'Ended' else original end;
$$;
create function guard_employment_end() returns trigger language plpgsql as $$
declare r hr_resignation;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 select * into r from hr_resignation where tenant=new.tenant and id=new.request_id for update;
 if r.status is distinct from 'APPROVED' or r.employee_id<>new.employee_id or r.last_working_date<>new.last_working_date then raise exception 'Approved resignation/date required'; end if;
 if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.subject=new.actor and m.employee_id=new.actor_employee_id and m.role='HR') then raise exception 'Independent HR actor required'; end if;
 if not exists(select 1 from hr_employee e where e.tenant=new.tenant and e.id=new.employee_id and e.version=new.new_version and e.status=new.old_status and e.start_date<new.inactive_date) then raise exception 'Employment snapshot/version mismatch'; end if;
 if exists(select 1 from hr_exit_task_current t where t.tenant=new.tenant and t.status='OPEN' and (t.request_id=new.request_id or t.assignee_id=new.employee_id)) then raise exception 'Open exit tasks must be completed or cancelled/reassigned'; end if;
 return new;
end $$;
create trigger guard_employment_end_insert before insert on hr_employment_end for each row execute function guard_employment_end();
create function reconcile_employment_end() returns trigger language plpgsql as $$
declare e hr_employment_end;
begin
 select * into e from hr_employment_end where tenant=new.tenant and employee_id=new.employee_id;
 if (select count(*) from hr_employment_end_handoff h where h.tenant=e.tenant and h.employee_id=e.employee_id and h.inactive_date=e.inactive_date and h.recorded_at=e.recorded_at and h.correlation_id=e.correlation_id)<>2 then raise exception 'Employment end handoffs mismatch'; end if;
 return null;
end $$;
create constraint trigger employment_end_reconciled after insert on hr_employment_end deferrable initially deferred for each row execute function reconcile_employment_end();
create constraint trigger employment_end_handoff_reconciled after insert on hr_employment_end_handoff deferrable initially deferred for each row execute function reconcile_employment_end();
create function guard_exit_lifecycle() returns trigger language plpgsql as $$
declare target_employee varchar;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 if TG_TABLE_NAME='hr_exit_task' then
  if exists(select 1 from hr_employment_end e where e.tenant=new.tenant and e.employee_id in(new.employee_id,new.assignee_id)) then raise exception 'Scheduled employment end prevents task assignment'; end if;
 else
  select employee_id into target_employee from hr_exit_task where tenant=new.tenant and id=new.task_id;
  if exists(select 1 from hr_employment_end e where e.tenant=new.tenant and (e.employee_id=target_employee or(new.action='REASSIGN' and e.employee_id=new.new_assignee_id))) then raise exception 'Scheduled employment end prevents task management'; end if;
 end if;
 return new;
end $$;
create trigger aaa_exit_task_lifecycle before insert on hr_exit_task for each row execute function guard_exit_lifecycle();
create trigger aaa_exit_management_lifecycle before insert on hr_exit_task_management for each row execute function guard_exit_lifecycle();
