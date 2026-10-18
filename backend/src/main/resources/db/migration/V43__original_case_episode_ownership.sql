-- Original-episode ownership only. Do not relax employee-wide cycle limits here.
do $$ begin
 if exists(select 1 from hr_resignation r join hr_employment_episode p on p.tenant=r.tenant and p.employee_id=r.employee_id
   where p.start_date<=(r.recorded_at at time zone 'UTC')::date and p.recorded_at<=r.recorded_at)
 then raise exception 'Ambiguous legacy case episode; reconcile provenance before migration'; end if;
end $$;
create table hr_case_episode (
 tenant varchar(80) not null,request_id varchar(40) not null,employee_id varchar(40) not null,
 episode_number integer not null check(episode_number=1),episode_start_date date not null,
 source_recorded_at timestamptz not null,linked_at timestamptz not null,
 origin varchar(20) not null check(origin in('COMMAND','MIGRATED_V42')),linked_by varchar(120) not null,correlation_id varchar(80) not null,
 primary key(tenant,request_id),foreign key(tenant,request_id) references hr_resignation(tenant,id),foreign key(tenant,employee_id) references hr_employee(tenant,id)
);
insert into hr_case_episode(tenant,request_id,employee_id,episode_number,episode_start_date,source_recorded_at,linked_at,origin,linked_by,correlation_id)
 select r.tenant,r.id,r.employee_id,1,e.start_date,r.recorded_at,clock_timestamp(),'MIGRATED_V42','SYSTEM_MIGRATION_V43','migration-v43'
 from hr_resignation r join hr_employee e on e.tenant=r.tenant and e.id=r.employee_id;
create trigger immutable_case_episode before update or delete on hr_case_episode for each row execute function prevent_audit_change();
create function guard_case_episode() returns trigger language plpgsql as $$
declare r hr_resignation;
begin
 select * into r from hr_resignation where tenant=new.tenant and id=new.request_id;
 if new.origin<>'COMMAND' or r.id is null or new.employee_id<>r.employee_id or new.episode_number<>1 or new.source_recorded_at<>r.recorded_at or new.linked_at<>r.recorded_at or new.linked_by<>r.requested_by
 or not exists(select 1 from hr_employee e where e.tenant=new.tenant and e.id=new.employee_id and e.start_date=new.episode_start_date)
 or not exists(select 1 from identity_membership m where m.subject=new.linked_by and m.tenant=new.tenant and m.employee_id=new.employee_id)
 or exists(select 1 from hr_employment_episode p where p.tenant=new.tenant and p.employee_id=new.employee_id and p.start_date<=(r.recorded_at at time zone 'UTC')::date)
 or not exists(select 1 from hr_resignation_audit a where a.tenant=new.tenant and a.request_id=new.request_id and a.old_status is null and a.new_status='SUBMITTED' and a.actor=new.linked_by and a.recorded_at=new.source_recorded_at and a.correlation_id=new.correlation_id)
 then raise exception 'Matching original-episode submission provenance required'; end if;
 return new;
end $$;
create trigger guard_case_episode_insert before insert on hr_case_episode for each row execute function guard_case_episode();
create function require_case_episode() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from hr_case_episode a where a.tenant=new.tenant and a.request_id=new.id and a.employee_id=new.employee_id and a.source_recorded_at=new.recorded_at)
 then raise exception 'Case episode ownership is required'; end if;
 return null;
end $$;
create constraint trigger resignation_episode_required after insert or update on hr_resignation deferrable initially deferred for each row execute function require_case_episode();
create view hr_episode_end_current as
 select e.*,a.episode_number,a.episode_start_date from hr_employment_end_current e join hr_case_episode a on a.tenant=e.tenant and a.request_id=e.request_id and a.employee_id=e.employee_id;
