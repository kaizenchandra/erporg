create table hr_rehire_access_revocation (
 tenant varchar(80) not null,activation_id varchar(40) not null,employee_id varchar(40) not null,subject varchar(120) not null,
 input_version bigint not null check(input_version>=0),new_version bigint not null check(new_version=input_version+1),
 actor varchar(120) not null,actor_employee_id varchar(40) not null,reason varchar(500) not null check(length(trim(reason))>0),
 recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null check(idempotency_key ~ '^[a-zA-Z0-9-]{8,80}$'),
 primary key(tenant,activation_id),unique(tenant,employee_id),unique(tenant,actor,idempotency_key),
 foreign key(tenant,activation_id) references hr_rehire_access(tenant,activation_id),
 foreign key(tenant,employee_id) references hr_employee(tenant,id),foreign key(tenant,actor_employee_id) references hr_employee(tenant,id),check(actor_employee_id<>employee_id)
);
create trigger immutable_rehire_access_revocation before update or delete on hr_rehire_access_revocation for each row execute function prevent_audit_change();
create function guard_rehire_access_revocation() returns trigger language plpgsql as $$
declare g hr_rehire_access;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 select * into g from hr_rehire_access where tenant=new.tenant and activation_id=new.activation_id;
 if g.employee_id is distinct from new.employee_id or g.subject is distinct from new.subject or new.recorded_at<g.recorded_at then raise exception 'Current grant and recording order required'; end if;
 if not exists(select 1 from hr_employee e where e.tenant=new.tenant and e.id=new.employee_id and e.version=new.new_version) then raise exception 'Revocation employee snapshot mismatch'; end if;
 if not exists(select 1 from identity_membership m join hr_employee e on e.tenant=m.tenant and e.id=m.employee_id where m.subject=new.actor and m.tenant=new.tenant and m.employee_id=new.actor_employee_id and m.role='HR' and hr_employment_status(e.tenant,e.id,e.status,(new.recorded_at at time zone 'UTC')::date)<>'Ended' and not exists(select 1 from hr_employment_episode p where p.tenant=m.tenant and p.employee_id=m.employee_id)) then raise exception 'Independent current HR required'; end if;
 return new;
end $$;
create trigger guard_rehire_access_revocation_insert before insert on hr_rehire_access_revocation for each row execute function guard_rehire_access_revocation();
create or replace view hr_rehire_handoff_current as select h.tenant,h.rehire_id,h.target,h.start_date,h.recorded_at,h.correlation_id,
 case when c.activation_id is null then h.status when h.target='ONBOARDING' then 'COMPLETED' when h.target='IDENTITY_REVIEW' then case when r.activation_id is null then 'LOCAL_ACCESS_GRANTED' else 'LOCAL_ACCESS_REVOKED' end else h.status end::varchar status
 from hr_rehire_handoff h left join hr_rehire_completion c on c.tenant=h.tenant and c.rehire_id=h.rehire_id left join hr_rehire_access_revocation r on r.tenant=c.tenant and r.activation_id=c.activation_id;
