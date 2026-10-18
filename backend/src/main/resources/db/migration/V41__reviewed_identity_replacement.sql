create table hr_local_access_grant (
 tenant varchar(80) not null,id varchar(40) not null,employee_id varchar(40) not null,subject varchar(120) not null unique,
 role varchar(20) not null check(role='EMPLOYEE'),epoch varchar(40) not null unique,ordinal integer not null check(ordinal in(1,2)),predecessor_id varchar(40),
 effective_date date not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,
 primary key(tenant,id),unique(tenant,employee_id,ordinal),foreign key(tenant,employee_id) references hr_employee(tenant,id),foreign key(subject) references identity_membership(subject),foreign key(tenant,predecessor_id) references hr_local_access_grant(tenant,id),
 check((ordinal=1 and predecessor_id is null) or (ordinal=2 and predecessor_id is not null))
);
insert into hr_local_access_grant select tenant,activation_id,employee_id,subject,role,epoch,1,null,effective_date,recorded_at,correlation_id from hr_rehire_access;
create trigger immutable_local_access_grant before update or delete on hr_local_access_grant for each row execute function prevent_audit_change();
create view hr_local_access_current as select distinct on(tenant,employee_id) * from hr_local_access_grant order by tenant,employee_id,ordinal desc;
alter table hr_rehire_access_revocation drop constraint hr_rehire_access_revocation_tenant_activation_id_fkey;
alter table hr_rehire_access_revocation add constraint revocation_grant_fk foreign key(tenant,activation_id) references hr_local_access_grant(tenant,id);
alter table hr_rehire_access_revocation drop constraint hr_rehire_access_revocation_tenant_employee_id_key;
create table hr_identity_replacement (
 tenant varchar(80) not null,id varchar(40) not null,employee_id varchar(40) not null,predecessor_id varchar(40) not null,subject varchar(120) not null,
 reason varchar(500) not null check(length(trim(reason))>0),input_version bigint not null check(input_version>=0),actor varchar(120) not null,actor_employee_id varchar(40) not null,
 recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null check(idempotency_key ~ '^[a-zA-Z0-9-]{8,80}$'),
 primary key(tenant,id),unique(tenant,actor,idempotency_key),foreign key(tenant,employee_id) references hr_employee(tenant,id),foreign key(tenant,predecessor_id) references hr_local_access_grant(tenant,id),foreign key(subject) references identity_membership(subject),check(actor_employee_id<>employee_id)
);
create index identity_replacement_history on hr_identity_replacement(tenant,employee_id,recorded_at,id);
create table hr_identity_replacement_decision (
 tenant varchar(80) not null,replacement_id varchar(40) not null,status varchar(20) not null check(status in('APPROVED','REJECTED')),reviewer varchar(120) not null,reviewer_employee_id varchar(40) not null,
 comment varchar(500) not null check(length(trim(comment))>0),review_version bigint not null check(review_version>=0),reviewed_at timestamptz not null,review_correlation_id varchar(80) not null,
 idempotency_key varchar(80) not null check(idempotency_key ~ '^[a-zA-Z0-9-]{8,80}$'),primary key(tenant,replacement_id),unique(tenant,reviewer,idempotency_key),foreign key(tenant,replacement_id) references hr_identity_replacement(tenant,id),foreign key(tenant,reviewer_employee_id) references hr_employee(tenant,id)
);
create trigger immutable_identity_replacement before update or delete on hr_identity_replacement for each row execute function prevent_audit_change();
create trigger immutable_identity_replacement_decision before update or delete on hr_identity_replacement_decision for each row execute function prevent_audit_change();
create view hr_identity_replacement_current as select r.tenant,r.id,r.employee_id,r.predecessor_id,r.subject,r.reason,r.input_version,r.actor,r.actor_employee_id,r.recorded_at,r.correlation_id,
 coalesce(d.status,'SUBMITTED')::varchar status,d.reviewer,d.reviewer_employee_id,d.comment,d.review_version,d.reviewed_at,d.review_correlation_id
 from hr_identity_replacement r left join hr_identity_replacement_decision d on d.tenant=r.tenant and d.replacement_id=r.id;
create function guard_identity_replacement() returns trigger language plpgsql as $$
declare r hr_identity_replacement;g hr_local_access_grant;who varchar;who_employee varchar;moment timestamptz;v bigint;target varchar;approved boolean=false;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 if TG_TABLE_NAME='hr_identity_replacement' then
  who=new.actor;who_employee=new.actor_employee_id;moment=new.recorded_at;target=new.employee_id;v=new.input_version;
  select * into g from hr_local_access_current where tenant=new.tenant and employee_id=target;
  if g.id is distinct from new.predecessor_id or g.ordinal<>1 or not exists(select 1 from hr_rehire_access_revocation x where x.tenant=g.tenant and x.activation_id=g.id and x.recorded_at<=moment) then raise exception 'Revoked original grant required'; end if;
  if not exists(select 1 from identity_membership m where m.subject=new.subject and m.tenant=new.tenant and m.employee_id=target) or exists(select 1 from hr_local_access_grant x where x.subject=new.subject) or exists(select 1 from hr_rehire_access_revocation x where x.subject=new.subject) then raise exception 'Unused mapped replacement identity required'; end if;
  if (select count(*) from hr_identity_replacement x where x.tenant=new.tenant and x.employee_id=target)>=20 or exists(select 1 from hr_identity_replacement_current x where x.tenant=new.tenant and x.employee_id=target and x.status in('SUBMITTED','APPROVED')) then raise exception 'Replacement pending/approved or attempt limit'; end if;
 else
  select * into r from hr_identity_replacement where tenant=new.tenant and id=new.replacement_id;
  approved=new.status='APPROVED';
  who=new.reviewer;who_employee=new.reviewer_employee_id;moment=new.reviewed_at;target=r.employee_id;v=new.review_version;
  if who=r.actor or who_employee in(r.actor_employee_id,target) or moment<r.recorded_at then raise exception 'Independent replacement reviewer/time required'; end if;
  if new.status='APPROVED' then
   select * into g from hr_local_access_current where tenant=new.tenant and employee_id=target;
   if g.id is distinct from r.predecessor_id or g.ordinal<>1 or v<>r.input_version or not exists(select 1 from hr_rehire_access_revocation x where x.tenant=g.tenant and x.activation_id=g.id and x.recorded_at<=moment) then raise exception 'Unchanged revoked predecessor required'; end if;
   if not exists(select 1 from identity_membership m where m.subject=r.subject and m.tenant=r.tenant and m.employee_id=target) or exists(select 1 from hr_local_access_grant x where x.subject=r.subject) or exists(select 1 from hr_rehire_access_revocation x where x.subject=r.subject) then raise exception 'Unused mapped replacement identity required'; end if;
  end if;
 end if;
 if who_employee=target or not exists(select 1 from identity_membership m join hr_employee e on e.tenant=m.tenant and e.id=m.employee_id where m.subject=who and m.tenant=new.tenant and m.employee_id=who_employee and m.role='HR' and hr_employment_status(e.tenant,e.id,e.status,(moment at time zone 'UTC')::date)<>'Ended' and not exists(select 1 from hr_employment_episode x where x.tenant=m.tenant and x.employee_id=m.employee_id) and not exists(select 1 from hr_rehire_access_revocation x where x.subject=m.subject)) then raise exception 'Independent current HR required'; end if;
 if not exists(select 1 from hr_employee e where e.tenant=new.tenant and e.id=target and e.version=v+case when approved then 1 else 0 end) then raise exception 'Replacement employee snapshot mismatch'; end if;
 if TG_TABLE_NAME='hr_identity_replacement' or approved then
  if not exists(select 1 from hr_employee e where e.tenant=new.tenant and e.id=target and hr_employment_status(e.tenant,e.id,e.status,(moment at time zone 'UTC')::date)='Active') then raise exception 'Active completed rehire required'; end if;
 end if;
 return new;
end $$;
create trigger guard_identity_replacement_insert before insert on hr_identity_replacement for each row execute function guard_identity_replacement();
create trigger guard_identity_replacement_decision_insert before insert on hr_identity_replacement_decision for each row execute function guard_identity_replacement();
create function guard_local_access_grant() returns trigger language plpgsql as $$
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 if new.ordinal=1 then
  if not exists(select 1 from hr_rehire_access g where g.tenant=new.tenant and g.activation_id=new.id and g.employee_id=new.employee_id and g.subject=new.subject and g.epoch=new.epoch and g.effective_date=new.effective_date and g.recorded_at=new.recorded_at and g.correlation_id=new.correlation_id) then raise exception 'Original grant snapshot required'; end if;
 else
  if not exists(select 1 from hr_identity_replacement_current r join hr_local_access_grant p on p.tenant=r.tenant and p.id=r.predecessor_id join hr_rehire_access_revocation x on x.tenant=p.tenant and x.activation_id=p.id where r.tenant=new.tenant and r.id=new.id and r.employee_id=new.employee_id and r.predecessor_id=new.predecessor_id and r.subject=new.subject and r.status='APPROVED' and p.employee_id=new.employee_id and p.ordinal=1 and p.subject<>new.subject and r.reviewed_at=new.recorded_at and r.review_correlation_id=new.correlation_id and new.effective_date=(r.reviewed_at at time zone 'UTC')::date) then raise exception 'Reviewed replacement grant required'; end if;
 end if;
 if exists(select 1 from hr_rehire_access_revocation x where x.subject=new.subject) then raise exception 'Held identity cannot receive a grant'; end if;
 return new;
end $$;
create trigger guard_local_access_grant_insert before insert on hr_local_access_grant for each row execute function guard_local_access_grant();
create function register_initial_local_grant() returns trigger language plpgsql as $$
begin
 insert into hr_local_access_grant values(new.tenant,new.activation_id,new.employee_id,new.subject,new.role,new.epoch,1,null,new.effective_date,new.recorded_at,new.correlation_id);
 return new;
end $$;
create trigger register_initial_local_grant after insert on hr_rehire_access for each row execute function register_initial_local_grant();
create function reconcile_identity_replacement() returns trigger language plpgsql as $$
begin
 if (new.status='APPROVED') <> exists(select 1 from hr_local_access_grant g where g.tenant=new.tenant and g.id=new.replacement_id and g.ordinal=2) then raise exception 'Replacement decision/grant mismatch'; end if;
 return null;
end $$;
create constraint trigger replacement_grant_reconciled after insert on hr_identity_replacement_decision deferrable initially deferred for each row execute function reconcile_identity_replacement();
create or replace function guard_rehire_access_revocation() returns trigger language plpgsql as $$
declare g hr_local_access_grant;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 select * into g from hr_local_access_current where tenant=new.tenant and id=new.activation_id;
 if g.employee_id is distinct from new.employee_id or g.subject is distinct from new.subject or new.recorded_at<g.recorded_at then raise exception 'Current grant and recording order required'; end if;
 if not exists(select 1 from hr_employee e where e.tenant=new.tenant and e.id=new.employee_id and e.version=new.new_version) then raise exception 'Revocation employee snapshot mismatch'; end if;
 if not exists(select 1 from identity_membership m join hr_employee e on e.tenant=m.tenant and e.id=m.employee_id where m.subject=new.actor and m.tenant=new.tenant and m.employee_id=new.actor_employee_id and m.role='HR' and not exists(select 1 from hr_rehire_access_revocation denied where denied.subject=m.subject) and hr_employment_status(e.tenant,e.id,e.status,(new.recorded_at at time zone 'UTC')::date)<>'Ended' and not exists(select 1 from hr_employment_episode p where p.tenant=m.tenant and p.employee_id=m.employee_id)) then raise exception 'Independent current HR required'; end if;
 return new;
end $$;
create or replace view hr_rehire_handoff_current as select h.tenant,h.rehire_id,h.target,h.start_date,h.recorded_at,h.correlation_id,
 case when c.activation_id is null then h.status when h.target='ONBOARDING' then 'COMPLETED' when h.target='IDENTITY_REVIEW' then case when r.activation_id is null then 'LOCAL_ACCESS_GRANTED' else 'LOCAL_ACCESS_REVOKED' end else h.status end::varchar status
 from hr_rehire_handoff h left join hr_rehire_completion c on c.tenant=h.tenant and c.rehire_id=h.rehire_id left join hr_local_access_current g on g.tenant=c.tenant and g.employee_id=c.employee_id left join hr_rehire_access_revocation r on r.tenant=g.tenant and r.activation_id=g.id;
