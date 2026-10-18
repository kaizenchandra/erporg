-- Core HR owns this transaction-consistent date projection. Null means no current-episode end.
create function hr_current_episode_end_date(t varchar,e varchar,as_of_date date) returns date language sql stable as $$
 select p.inactive_date from hr_employment_episode_state p
 where p.tenant=t and p.employee_id=e and p.start_date<=as_of_date
 order by p.start_date desc,p.episode_number desc limit 1;
$$;
-- Preserve the departing case's close-on-end policy. Only assignee end lookup changes.
create or replace function guard_exit_lifecycle() returns trigger language plpgsql as $$
declare target_employee varchar;moment date;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 moment=(new.recorded_at at time zone 'UTC')::date;
 if TG_TABLE_NAME='hr_exit_task' then
  if exists(select 1 from hr_employment_end_current e where e.tenant=new.tenant and e.employee_id=new.employee_id)
   or hr_current_episode_end_date(new.tenant,new.assignee_id,moment) is not null
  then raise exception 'Scheduled employment end prevents task assignment'; end if;
 else
  select employee_id into target_employee from hr_exit_task where tenant=new.tenant and id=new.task_id;
  if exists(select 1 from hr_employment_end_current e where e.tenant=new.tenant and e.employee_id=target_employee)
   or(new.action='REASSIGN' and hr_current_episode_end_date(new.tenant,new.new_assignee_id,moment) is not null)
  then raise exception 'Scheduled employment end prevents task management'; end if;
 end if;
 return new;
end $$;
