-- Compatibility bridge for the original-episode V42 submission contract.
-- New writers keep their explicit COMMAND link. A complete older writer may derive
-- COMPAT_COMMAND at deferred validation; incomplete/audit-less writes still fail.
alter table hr_case_episode drop constraint hr_case_episode_origin_check;
alter table hr_case_episode add constraint hr_case_episode_origin_check check(origin in('COMMAND','COMPAT_COMMAND','MIGRATED_V42'));
create or replace function guard_case_episode() returns trigger language plpgsql as $$
declare r hr_resignation;
begin
 select * into r from hr_resignation where tenant=new.tenant and id=new.request_id;
 if new.origin not in('COMMAND','COMPAT_COMMAND') or r.id is null or new.employee_id<>r.employee_id or new.episode_number<>1 or new.source_recorded_at<>r.recorded_at or new.linked_at<>r.recorded_at or new.linked_by<>r.requested_by
 or not exists(select 1 from hr_employee e where e.tenant=new.tenant and e.id=new.employee_id and e.start_date=new.episode_start_date)
 or not exists(select 1 from identity_membership m where m.subject=new.linked_by and m.tenant=new.tenant and m.employee_id=new.employee_id)
 or exists(select 1 from hr_employment_episode p where p.tenant=new.tenant and p.employee_id=new.employee_id and p.start_date<=(r.recorded_at at time zone 'UTC')::date)
 or not exists(select 1 from hr_resignation_audit a where a.tenant=new.tenant and a.request_id=new.request_id and a.old_status is null and a.new_status='SUBMITTED' and a.actor=new.linked_by and a.recorded_at=new.source_recorded_at and a.correlation_id=new.correlation_id)
 then raise exception 'Matching original-episode submission provenance required'; end if;
 return new;
end $$;
create or replace function require_case_episode() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from hr_case_episode a where a.tenant=new.tenant and a.request_id=new.id) then
  insert into hr_case_episode(tenant,request_id,employee_id,episode_number,episode_start_date,source_recorded_at,linked_at,origin,linked_by,correlation_id)
   select r.tenant,r.id,r.employee_id,1,e.start_date,r.recorded_at,r.recorded_at,'COMPAT_COMMAND',r.requested_by,a.correlation_id
   from hr_resignation r join hr_employee e on e.tenant=r.tenant and e.id=r.employee_id
   join hr_resignation_audit a on a.tenant=r.tenant and a.request_id=r.id and a.old_status is null and a.new_status='SUBMITTED' and a.actor=r.requested_by and a.recorded_at=r.recorded_at and a.reason=r.reason and a.effective_date=r.last_working_date
   where r.tenant=new.tenant and r.id=new.id;
 end if;
 if not exists(select 1 from hr_case_episode a where a.tenant=new.tenant and a.request_id=new.id and a.employee_id=new.employee_id and a.source_recorded_at=new.recorded_at)
 then raise exception 'Case episode ownership is required'; end if;
 return null;
end $$;
