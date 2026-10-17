alter table hr_resignation add constraint resignation_employee_link unique(tenant,id,employee_id);
create table hr_exit_task (
 tenant varchar(80) not null,id varchar(40) not null,request_id varchar(40) not null,employee_id varchar(40) not null,
 assignee_id varchar(40) not null,ordinal int not null check(ordinal between 1 and 50),
 title varchar(120) not null check(length(trim(title))>0),instructions varchar(500) not null check(length(trim(instructions))>0),
 due_date date not null,reason varchar(500) not null check(length(trim(reason))>0),created_by varchar(120) not null,
 recorded_at timestamptz not null,idempotency_key varchar(80) not null,input_version bigint not null check(input_version=ordinal-1),
 status varchar(20) not null check(status in('OPEN','COMPLETED')),version bigint not null check(version in(0,1)),
 completed_by varchar(120),completion_comment varchar(500),completed_at timestamptz,completion_key varchar(80),
 primary key(tenant,id),unique(tenant,request_id,ordinal),unique(tenant,created_by,idempotency_key),
 foreign key(tenant,request_id,employee_id) references hr_resignation(tenant,id,employee_id),
 foreign key(tenant,assignee_id) references hr_employee(tenant,id),
 check((status='OPEN' and version=0 and completed_by is null and completion_comment is null and completed_at is null and completion_key is null)
 or(status='COMPLETED' and version=1 and completed_by is not null and completion_comment is not null and length(trim(completion_comment))>0 and completed_at is not null and completion_key is not null))
);
create index exit_task_assignee_page on hr_exit_task(tenant,assignee_id,recorded_at desc,id);
create table hr_exit_task_audit(
 id bigint generated always as identity primary key,tenant varchar(80) not null,task_id varchar(40) not null,
 old_status varchar(20),new_status varchar(20) not null,actor varchar(120) not null,reason varchar(500) not null,
 effective_date date not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,
 unique(tenant,task_id,new_status),foreign key(tenant,task_id) references hr_exit_task(tenant,id),
 check((old_status is null and new_status='OPEN') or(old_status='OPEN' and new_status='COMPLETED'))
);
create trigger immutable_exit_task_audit before update or delete on hr_exit_task_audit for each row execute function prevent_audit_change();
create function protect_exit_task() returns trigger language plpgsql as $$
declare case_status varchar; next_ordinal int;
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
  if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.subject=new.completed_by and m.employee_id=new.assignee_id) then raise exception 'Completion must belong to assigned employee'; end if;
 end if;
 return new;
end $$;
create trigger protect_exit_task_write before insert or update or delete on hr_exit_task for each row execute function protect_exit_task();
create function reconcile_exit_task() returns trigger language plpgsql as $$
declare t hr_exit_task;
begin
 if TG_TABLE_NAME='hr_exit_task' then select * into t from hr_exit_task where tenant=new.tenant and id=new.id;
 else select * into t from hr_exit_task where tenant=new.tenant and id=new.task_id; end if;
 if (select count(*) from hr_exit_task_audit a where a.tenant=t.tenant and a.task_id=t.id)<>(case when t.status='OPEN' then 1 else 2 end)
 or not exists(select 1 from hr_exit_task_audit a where a.tenant=t.tenant and a.task_id=t.id and a.new_status='OPEN' and a.actor=t.created_by and a.reason=t.reason and a.effective_date=t.due_date and a.recorded_at=t.recorded_at)
 or(t.status='COMPLETED' and not exists(select 1 from hr_exit_task_audit a where a.tenant=t.tenant and a.task_id=t.id and a.new_status='COMPLETED' and a.actor=t.completed_by and a.reason=t.completion_comment and a.effective_date=t.due_date and a.recorded_at=t.completed_at))
 then raise exception 'Exit task audit mismatch'; end if;
 return null;
end $$;
create constraint trigger exit_task_reconciled after insert or update on hr_exit_task deferrable initially deferred for each row execute function reconcile_exit_task();
create constraint trigger exit_task_audit_reconciled after insert on hr_exit_task_audit deferrable initially deferred for each row execute function reconcile_exit_task();
