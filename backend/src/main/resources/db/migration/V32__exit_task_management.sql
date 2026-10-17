create table hr_exit_task_management (
 tenant varchar(80) not null,task_id varchar(40) not null,ordinal int not null check(ordinal between 1 and 100),
 action varchar(20) not null check(action in('REASSIGN','CANCEL')),
 old_assignee_id varchar(40) not null,new_assignee_id varchar(40) not null,
 old_status varchar(20) not null check(old_status='OPEN'),new_status varchar(20) not null check(new_status in('OPEN','CANCELLED')),
 actor varchar(120) not null,actor_employee_id varchar(40) not null,reason varchar(500) not null check(length(trim(reason))>0),
 input_version bigint not null check(input_version=ordinal-1),idempotency_key varchar(80) not null,
 recorded_at timestamptz not null,correlation_id varchar(80) not null,
 primary key(tenant,task_id,ordinal),unique(tenant,actor,idempotency_key),
 foreign key(tenant,task_id) references hr_exit_task(tenant,id),
 foreign key(tenant,old_assignee_id) references hr_employee(tenant,id),foreign key(tenant,new_assignee_id) references hr_employee(tenant,id),
 foreign key(tenant,actor_employee_id) references hr_employee(tenant,id),
 check((action='REASSIGN' and old_assignee_id<>new_assignee_id and new_status='OPEN') or(action='CANCEL' and old_assignee_id=new_assignee_id and new_status='CANCELLED'))
);
create trigger immutable_exit_management before update or delete on hr_exit_task_management for each row execute function prevent_audit_change();
create function guard_exit_management() returns trigger language plpgsql as $$
declare t hr_exit_task; previous hr_exit_task_management; current_assignee varchar; current_status varchar; next_ordinal int;
begin
 select * into t from hr_exit_task where tenant=new.tenant and id=new.task_id for update;
 select * into previous from hr_exit_task_management where tenant=new.tenant and task_id=new.task_id order by ordinal desc limit 1;
 current_assignee:=coalesce(previous.new_assignee_id,t.assignee_id);
 current_status:=coalesce(previous.new_status,t.status);
 next_ordinal:=coalesce(previous.ordinal,0)+1;
 if t.id is null or t.status<>'OPEN' or current_status<>'OPEN' or new.ordinal<>next_ordinal or new.old_assignee_id<>current_assignee then raise exception 'Exit task state/version/assignment changed'; end if;
 if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.subject=new.actor and m.employee_id=new.actor_employee_id and m.role='HR' and m.employee_id<>t.employee_id) then raise exception 'Independent HR coordinator required'; end if;
 if new.action='REASSIGN' and not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.employee_id=new.new_assignee_id) then raise exception 'Configured assignee identity required'; end if;
 return new;
end $$;
create trigger guard_exit_management_insert before insert on hr_exit_task_management for each row execute function guard_exit_management();
-- The original completion guard now validates against the current append-only assignment.
create or replace function protect_exit_task() returns trigger language plpgsql as $$
declare case_status varchar; next_ordinal int; current_assignee varchar; managed_status varchar;
begin
 if TG_OP='DELETE' then raise exception 'Exit task history cannot be deleted'; end if;
 if TG_OP='INSERT' then
  select status into case_status from hr_resignation where tenant=new.tenant and id=new.request_id for update;
  select coalesce(max(ordinal),0)+1 into next_ordinal from hr_exit_task where tenant=new.tenant and request_id=new.request_id;
  if case_status is distinct from 'APPROVED' or new.ordinal<>next_ordinal or new.status<>'OPEN' then raise exception 'Approved case and sequential open task required'; end if;
 else
  if old.status<>'OPEN' or new.status<>'COMPLETED' or new.version<>old.version+1 or
  row(new.tenant,new.id,new.request_id,new.employee_id,new.assignee_id,new.ordinal,new.title,new.instructions,new.due_date,new.reason,new.created_by,new.recorded_at,new.idempotency_key,new.input_version)
  is distinct from row(old.tenant,old.id,old.request_id,old.employee_id,old.assignee_id,old.ordinal,old.title,old.instructions,old.due_date,old.reason,old.created_by,old.recorded_at,old.idempotency_key,old.input_version)
  then raise exception 'Invalid exit task transition or snapshot mutation'; end if;
  select new_assignee_id,new_status into current_assignee,managed_status from hr_exit_task_management where tenant=old.tenant and task_id=old.id order by ordinal desc limit 1;
  if managed_status='CANCELLED' then raise exception 'Cancelled task cannot complete'; end if;
  if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.subject=new.completed_by and m.employee_id=coalesce(current_assignee,new.assignee_id)) then raise exception 'Completion must belong to current assigned employee'; end if;
 end if;
 return new;
end $$;
-- Core HR owns this synchronous projection; no cross-module SQL or materialised cache.
create view hr_exit_task_current as
select t.tenant,t.id,t.request_id,t.employee_id,coalesce(m.new_assignee_id,t.assignee_id) assignee_id,t.ordinal,t.title,t.instructions,t.due_date,t.reason,t.created_by,t.recorded_at,
 case when t.status='COMPLETED' then 'COMPLETED' else coalesce(m.new_status,t.status) end status,
 t.version+coalesce(m.ordinal,0) version,t.completed_by,t.completion_comment,t.completed_at
from hr_exit_task t left join lateral(select new_assignee_id,new_status,ordinal from hr_exit_task_management where tenant=t.tenant and task_id=t.id order by ordinal desc limit 1)m on true;
