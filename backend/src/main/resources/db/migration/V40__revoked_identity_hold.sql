create unique index hr_rehire_access_revocation_subject on hr_rehire_access_revocation(subject);
create or replace function guard_rehire_access_revocation() returns trigger language plpgsql as $$
declare g hr_rehire_access;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.tenant,87301));
 select * into g from hr_rehire_access where tenant=new.tenant and activation_id=new.activation_id;
 if g.employee_id is distinct from new.employee_id or g.subject is distinct from new.subject or new.recorded_at<g.recorded_at then raise exception 'Current grant and recording order required'; end if;
 if not exists(select 1 from hr_employee e where e.tenant=new.tenant and e.id=new.employee_id and e.version=new.new_version) then raise exception 'Revocation employee snapshot mismatch'; end if;
 if not exists(select 1 from identity_membership m join hr_employee e on e.tenant=m.tenant and e.id=m.employee_id where m.subject=new.actor and m.tenant=new.tenant and m.employee_id=new.actor_employee_id and m.role='HR' and not exists(select 1 from hr_rehire_access_revocation denied where denied.subject=m.subject) and hr_employment_status(e.tenant,e.id,e.status,(new.recorded_at at time zone 'UTC')::date)<>'Ended' and not exists(select 1 from hr_employment_episode p where p.tenant=m.tenant and p.employee_id=m.employee_id)) then raise exception 'Independent current HR required'; end if;
 return new;
end $$;
