create table hr_rehire (
 tenant varchar(80) not null,id varchar(40) not null,employee_id varchar(40) not null,start_date date not null,
 title varchar(120) not null check(length(trim(title))>0),department varchar(120) not null check(length(trim(department))>0),location varchar(120) not null check(length(trim(location))>0),manager_id varchar(40) not null,
 prior_inactive_date date not null check(start_date>=prior_inactive_date),actor varchar(120) not null,actor_employee_id varchar(40) not null,reason varchar(500) not null check(length(trim(reason))>0),
 input_version bigint not null check(input_version>=0),recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,id),unique(tenant,actor,idempotency_key),foreign key(tenant,employee_id) references hr_employee(tenant,id),foreign key(tenant,manager_id) references hr_employee(tenant,id),foreign key(tenant,actor_employee_id) references hr_employee(tenant,id),check(actor_employee_id<>employee_id and manager_id<>employee_id)
);
create index hr_rehire_employee on hr_rehire(tenant,employee_id,recorded_at,id);
create table hr_rehire_decision (
 tenant varchar(80) not null,rehire_id varchar(40) not null,status varchar(20) not null check(status in('APPROVED','REJECTED')),
 reviewer varchar(120) not null,reviewer_employee_id varchar(40) not null,comment varchar(500) not null check(length(trim(comment))>0),review_version bigint not null check(review_version>=0),
 reviewed_at timestamptz not null,review_correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,rehire_id),unique(tenant,reviewer,idempotency_key),foreign key(tenant,rehire_id) references hr_rehire(tenant,id),foreign key(tenant,reviewer_employee_id) references hr_employee(tenant,id)
);
create table hr_employment_episode (
 tenant varchar(80) not null,employee_id varchar(40) not null,number integer not null check(number=2),rehire_id varchar(40) not null,
 start_date date not null,prior_inactive_date date not null check(start_date>=prior_inactive_date),status varchar(20) not null check(status='Onboarding'),recorded_at timestamptz not null,correlation_id varchar(80) not null,
 primary key(tenant,employee_id,number),unique(tenant,rehire_id),foreign key(tenant,rehire_id) references hr_rehire_decision(tenant,rehire_id),foreign key(tenant,employee_id) references hr_employee(tenant,id)
);
create table hr_rehire_handoff (
 tenant varchar(80) not null,rehire_id varchar(40) not null,target varchar(30) not null check(target in('ONBOARDING','PAYROLL','IDENTITY_REVIEW')),start_date date not null,
 recorded_at timestamptz not null,correlation_id varchar(80) not null,status varchar(20) not null check(status='PENDING'),
 primary key(tenant,rehire_id,target),foreign key(tenant,rehire_id) references hr_rehire_decision(tenant,rehire_id)
);
create trigger immutable_rehire before update or delete on hr_rehire for each row execute function prevent_audit_change();
create trigger immutable_rehire_decision before update or delete on hr_rehire_decision for each row execute function prevent_audit_change();
create trigger immutable_employment_episode before update or delete on hr_employment_episode for each row execute function prevent_audit_change();
create trigger immutable_rehire_handoff before update or delete on hr_rehire_handoff for each row execute function prevent_audit_change();
create view hr_rehire_current as
 select r.tenant,r.id,r.employee_id,r.start_date,r.title,r.department,r.location,r.manager_id,r.prior_inactive_date,r.actor,r.actor_employee_id,r.reason,r.input_version,r.recorded_at,r.correlation_id,
 coalesce(d.status,'SUBMITTED')::varchar as status,d.reviewer,d.reviewer_employee_id,d.comment,d.review_version,d.reviewed_at,d.review_correlation_id
 from hr_rehire r left join hr_rehire_decision d on d.tenant=r.tenant and d.rehire_id=r.id;
create or replace function hr_employment_status(t varchar,e varchar,original varchar,as_of_date date) returns varchar language sql stable as $$
 select case when exists(select 1 from hr_employment_episode p where p.tenant=t and p.employee_id=e and p.start_date<=as_of_date) then 'Onboarding'
 when exists(select 1 from hr_employment_end_current x where x.tenant=t and x.employee_id=e and x.inactive_date<=as_of_date) then 'Ended' else original end;
$$;
create function hr_employment_start(t varchar,e varchar,original date,as_of_date date) returns date language sql stable as $$
 select coalesce((select p.start_date from hr_employment_episode p where p.tenant=t and p.employee_id=e and p.start_date<=as_of_date),original);
$$;
create function hr_employment_value(t varchar,e varchar,f varchar,original varchar,as_of_date date) returns varchar language sql stable as $$
 select coalesce((select x.value from (
 select v.value,v.effective_date,v.recorded_at,v.id::text as tie from hr_version v where v.tenant=t and v.employee_id=e and v.field=f and v.effective_date<=as_of_date
 union all
 select case f when 'title' then r.title when 'department' then r.department when 'location' then r.location when 'managerId' then r.manager_id when 'positionCode' then '' end,p.start_date,p.recorded_at,p.rehire_id from hr_employment_episode p join hr_rehire r on r.tenant=p.tenant and r.id=p.rehire_id where p.tenant=t and p.employee_id=e and p.start_date<=as_of_date and f in('title','department','location','managerId','positionCode')
 ) x order by x.effective_date desc,x.recorded_at desc,x.tie desc limit 1),original);
$$;
create function guard_rehire() returns trigger language plpgsql as $$
declare r hr_rehire; ended date; moment date;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 if TG_TABLE_NAME='hr_rehire' then
  moment=(new.recorded_at at time zone 'UTC')::date;
  select inactive_date into ended from hr_employment_end_current where tenant=new.tenant and employee_id=new.employee_id;
  if ended is null or ended>moment or new.prior_inactive_date<>ended or new.start_date<moment or new.start_date>(moment+interval '1 year')::date then raise exception 'Effective prior end and current rehire date required'; end if;
  if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.subject=new.actor and m.employee_id=new.actor_employee_id and m.role='HR') then raise exception 'HR requester required'; end if;
  if not exists(select 1 from hr_employee e where e.tenant=new.tenant and e.id=new.employee_id and e.version=new.input_version) then raise exception 'Employee snapshot mismatch'; end if;
  if (select count(*) from hr_rehire where tenant=new.tenant and employee_id=new.employee_id)>=20 or exists(select 1 from hr_rehire_current x where x.tenant=new.tenant and x.employee_id=new.employee_id and x.status in('SUBMITTED','APPROVED')) then raise exception 'Pending/approved rehire or attempt limit'; end if;
 else
  select * into r from hr_rehire where tenant=new.tenant and id=new.rehire_id;
  moment=(new.reviewed_at at time zone 'UTC')::date;
  if new.reviewer=r.actor or new.reviewer_employee_id in(r.actor_employee_id,r.employee_id) or new.reviewed_at<r.recorded_at then raise exception 'Independent reviewer/time required'; end if;
  if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.subject=new.reviewer and m.employee_id=new.reviewer_employee_id and m.role='HR') then raise exception 'HR reviewer required'; end if;
  if not exists(select 1 from hr_employee e where e.tenant=r.tenant and e.id=r.employee_id and e.version=new.review_version+case when new.status='APPROVED' then 1 else 0 end) then raise exception 'Review version mismatch'; end if;
  if new.status='APPROVED' then
   select inactive_date into ended from hr_employment_end_current where tenant=r.tenant and employee_id=r.employee_id;
   if ended is distinct from r.prior_inactive_date or ended>moment or r.start_date<moment or r.start_date>(moment+interval '1 year')::date or r.input_version<>new.review_version then raise exception 'Unchanged ended snapshot/current start required'; end if;
  end if;
 end if;
 return new;
end $$;
create trigger guard_rehire_insert before insert on hr_rehire for each row execute function guard_rehire();
create trigger guard_rehire_decision_insert before insert on hr_rehire_decision for each row execute function guard_rehire();
create function reconcile_rehire() returns trigger language plpgsql as $$
declare r hr_rehire; d hr_rehire_decision;
begin
 select * into r from hr_rehire where tenant=new.tenant and id=new.rehire_id;
 select * into d from hr_rehire_decision where tenant=new.tenant and rehire_id=new.rehire_id;
 if d.status='APPROVED' then
  if not exists(select 1 from hr_employment_episode p where p.tenant=r.tenant and p.rehire_id=r.id and p.employee_id=r.employee_id and p.start_date=r.start_date and p.prior_inactive_date=r.prior_inactive_date and p.recorded_at=d.reviewed_at and p.correlation_id=d.review_correlation_id)
  or (select count(*) from hr_rehire_handoff h where h.tenant=r.tenant and h.rehire_id=r.id and h.start_date=r.start_date and h.recorded_at=d.reviewed_at and h.correlation_id=d.review_correlation_id)<>3 then raise exception 'Rehire episode/handoff mismatch'; end if;
 elsif exists(select 1 from hr_employment_episode p where p.tenant=r.tenant and p.rehire_id=r.id) or exists(select 1 from hr_rehire_handoff h where h.tenant=r.tenant and h.rehire_id=r.id) then raise exception 'Rejected rehire has no episode/handoff'; end if;
 return null;
end $$;
create constraint trigger rehire_decision_reconciled after insert on hr_rehire_decision deferrable initially deferred for each row execute function reconcile_rehire();
create constraint trigger rehire_episode_reconciled after insert on hr_employment_episode deferrable initially deferred for each row execute function reconcile_rehire();
create constraint trigger rehire_handoff_reconciled after insert on hr_rehire_handoff deferrable initially deferred for each row execute function reconcile_rehire();
