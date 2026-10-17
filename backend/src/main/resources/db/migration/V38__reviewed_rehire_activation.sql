create table hr_rehire_activation (
 tenant varchar(80) not null,id varchar(40) not null,rehire_id varchar(40) not null,employee_id varchar(40) not null,subject varchar(120) not null,
 reason varchar(500) not null check(length(trim(reason))>0),input_version bigint not null check(input_version>=0),actor varchar(120) not null,actor_employee_id varchar(40) not null,
 recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,id),unique(tenant,actor,idempotency_key),foreign key(tenant,rehire_id) references hr_employment_episode(tenant,rehire_id),foreign key(tenant,employee_id) references hr_employee(tenant,id),foreign key(subject) references identity_membership(subject),foreign key(tenant,actor_employee_id) references hr_employee(tenant,id),check(actor_employee_id<>employee_id)
);
create index hr_rehire_activation_employee on hr_rehire_activation(tenant,employee_id,recorded_at,id);
create table hr_rehire_activation_decision (
 tenant varchar(80) not null,activation_id varchar(40) not null,status varchar(20) not null check(status in('APPROVED','REJECTED')),reviewer varchar(120) not null,reviewer_employee_id varchar(40) not null,
 comment varchar(500) not null check(length(trim(comment))>0),review_version bigint not null check(review_version>=0),reviewed_at timestamptz not null,review_correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,activation_id),unique(tenant,reviewer,idempotency_key),foreign key(tenant,activation_id) references hr_rehire_activation(tenant,id),foreign key(tenant,reviewer_employee_id) references hr_employee(tenant,id)
);
create table hr_rehire_completion (
 tenant varchar(80) not null,activation_id varchar(40) not null,rehire_id varchar(40) not null,employee_id varchar(40) not null,subject varchar(120) not null,role varchar(20) not null check(role='EMPLOYEE'),
 effective_date date not null,input_version bigint not null check(input_version>=0),new_version bigint not null check(new_version=input_version+1),actor varchar(120) not null,reason varchar(500) not null check(length(trim(reason))>0),recorded_at timestamptz not null,correlation_id varchar(80) not null,
 primary key(tenant,employee_id),unique(tenant,activation_id),unique(tenant,rehire_id),foreign key(tenant,activation_id) references hr_rehire_activation_decision(tenant,activation_id),foreign key(tenant,rehire_id) references hr_employment_episode(tenant,rehire_id),check(effective_date=(recorded_at at time zone 'UTC')::date)
);
create table hr_rehire_access (
 tenant varchar(80) not null,employee_id varchar(40) not null,activation_id varchar(40) not null,subject varchar(120) not null unique,role varchar(20) not null check(role='EMPLOYEE'),epoch varchar(40) not null unique,
 effective_date date not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,
 primary key(tenant,employee_id),unique(tenant,activation_id),foreign key(tenant,activation_id) references hr_rehire_completion(tenant,activation_id),foreign key(subject) references identity_membership(subject)
);
create trigger immutable_rehire_activation before update or delete on hr_rehire_activation for each row execute function prevent_audit_change();
create trigger immutable_rehire_activation_decision before update or delete on hr_rehire_activation_decision for each row execute function prevent_audit_change();
create trigger immutable_rehire_completion before update or delete on hr_rehire_completion for each row execute function prevent_audit_change();
create trigger immutable_rehire_access before update or delete on hr_rehire_access for each row execute function prevent_audit_change();
create view hr_rehire_activation_current as select a.tenant,a.id,a.rehire_id,a.employee_id,a.subject,a.reason,a.input_version,a.actor,a.actor_employee_id,a.recorded_at,a.correlation_id,coalesce(d.status,'SUBMITTED')::varchar status,d.reviewer,d.reviewer_employee_id,d.comment,d.review_version,d.reviewed_at,d.review_correlation_id from hr_rehire_activation a left join hr_rehire_activation_decision d on d.tenant=a.tenant and d.activation_id=a.id;
create or replace function hr_employment_status(t varchar,e varchar,original varchar,as_of_date date) returns varchar language sql stable as $$
 select case when exists(select 1 from hr_employment_episode p where p.tenant=t and p.employee_id=e and p.start_date<=as_of_date) then
 case when exists(select 1 from hr_rehire_completion c where c.tenant=t and c.employee_id=e and c.effective_date<=as_of_date) then 'Active' else 'Onboarding' end
 when exists(select 1 from hr_employment_end_current x where x.tenant=t and x.employee_id=e and x.inactive_date<=as_of_date) then 'Ended' else original end;
$$;
create view hr_rehire_handoff_current as select h.tenant,h.rehire_id,h.target,h.start_date,h.recorded_at,h.correlation_id,
 case when c.activation_id is null then h.status when h.target='ONBOARDING' then 'COMPLETED' when h.target='IDENTITY_REVIEW' then 'LOCAL_ACCESS_GRANTED' else h.status end::varchar status
 from hr_rehire_handoff h left join hr_rehire_completion c on c.tenant=h.tenant and c.rehire_id=h.rehire_id;
create function guard_rehire_activation() returns trigger language plpgsql as $$
declare p hr_employment_episode; a hr_rehire_activation; moment date;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 if TG_TABLE_NAME='hr_rehire_activation' then
  moment=(new.recorded_at at time zone 'UTC')::date;
  select * into p from hr_employment_episode where tenant=new.tenant and rehire_id=new.rehire_id;
  if p.employee_id is distinct from new.employee_id or p.start_date>moment or exists(select 1 from hr_rehire_completion c where c.tenant=new.tenant and c.employee_id=new.employee_id) then raise exception 'Started onboarding episode required'; end if;
  if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.employee_id=new.employee_id and m.subject=new.subject) then raise exception 'Mapped nominated identity required'; end if;
  if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.employee_id=new.actor_employee_id and m.subject=new.actor and m.role='HR' and not exists(select 1 from hr_employment_episode x where x.tenant=m.tenant and x.employee_id=m.employee_id)) then raise exception 'HR requester required'; end if;
  if not exists(select 1 from hr_employee e where e.tenant=new.tenant and e.id=new.employee_id and e.version=new.input_version) then raise exception 'Employee snapshot mismatch'; end if;
  if (select count(*) from hr_rehire_activation where tenant=new.tenant and employee_id=new.employee_id)>=20 or exists(select 1 from hr_rehire_activation_current x where x.tenant=new.tenant and x.employee_id=new.employee_id and x.status in('SUBMITTED','APPROVED')) then raise exception 'Pending/approved completion or attempt limit'; end if;
 else
  select * into a from hr_rehire_activation where tenant=new.tenant and id=new.activation_id;
  select * into p from hr_employment_episode where tenant=a.tenant and rehire_id=a.rehire_id;
  moment=(new.reviewed_at at time zone 'UTC')::date;
  if new.reviewer=a.actor or new.reviewer_employee_id in(a.actor_employee_id,a.employee_id) or new.reviewed_at<a.recorded_at then raise exception 'Independent reviewer/time required'; end if;
  if not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.employee_id=new.reviewer_employee_id and m.subject=new.reviewer and m.role='HR' and not exists(select 1 from hr_employment_episode x where x.tenant=m.tenant and x.employee_id=m.employee_id)) then raise exception 'HR reviewer required'; end if;
  if not exists(select 1 from hr_employee e where e.tenant=a.tenant and e.id=a.employee_id and e.version=new.review_version+case when new.status='APPROVED' then 1 else 0 end) then raise exception 'Review version mismatch'; end if;
  if new.status='APPROVED' and (p.start_date>moment or new.review_version<>a.input_version or not exists(select 1 from identity_membership m where m.tenant=a.tenant and m.employee_id=a.employee_id and m.subject=a.subject)) then raise exception 'Started unchanged episode/mapped identity required'; end if;
 end if;
 return new;
end $$;
create trigger guard_rehire_activation_insert before insert on hr_rehire_activation for each row execute function guard_rehire_activation();
create trigger guard_rehire_activation_decision_insert before insert on hr_rehire_activation_decision for each row execute function guard_rehire_activation();
create function reconcile_rehire_activation() returns trigger language plpgsql as $$
declare a hr_rehire_activation; d hr_rehire_activation_decision;
begin
 select * into a from hr_rehire_activation where tenant=new.tenant and id=new.activation_id;
 select * into d from hr_rehire_activation_decision where tenant=new.tenant and activation_id=new.activation_id;
 if d.status='APPROVED' then
  if not exists(select 1 from hr_rehire_completion c join hr_rehire_access g on g.tenant=c.tenant and g.activation_id=c.activation_id and g.employee_id=c.employee_id and g.subject=c.subject and g.role=c.role and g.effective_date=c.effective_date and g.recorded_at=c.recorded_at and g.correlation_id=c.correlation_id
  where c.tenant=a.tenant and c.activation_id=a.id and c.rehire_id=a.rehire_id and c.employee_id=a.employee_id and c.subject=a.subject and c.input_version=d.review_version and c.actor=d.reviewer and c.reason=d.comment and c.recorded_at=d.reviewed_at and c.correlation_id=d.review_correlation_id)
  then raise exception 'Completion/access grant mismatch'; end if;
 elsif exists(select 1 from hr_rehire_completion c where c.tenant=a.tenant and c.activation_id=a.id) or exists(select 1 from hr_rehire_access g where g.tenant=a.tenant and g.activation_id=a.id) then raise exception 'Rejected completion cannot grant access'; end if;
 return null;
end $$;
create constraint trigger rehire_activation_reconciled after insert on hr_rehire_activation_decision deferrable initially deferred for each row execute function reconcile_rehire_activation();
create constraint trigger rehire_completion_reconciled after insert on hr_rehire_completion deferrable initially deferred for each row execute function reconcile_rehire_activation();
create constraint trigger rehire_access_reconciled after insert on hr_rehire_access deferrable initially deferred for each row execute function reconcile_rehire_activation();
