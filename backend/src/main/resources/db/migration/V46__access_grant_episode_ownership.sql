-- Core HR owns this private provenance projection; it does not grant permissions.
create view hr_access_grant_episode_source as
 select g.tenant,g.id grant_id,g.employee_id,p.number episode_number,p.rehire_id,
 p.start_date episode_start_date,g.ordinal grant_sequence
 from hr_local_access_grant g join hr_local_access_grant first_grant
 on first_grant.tenant=g.tenant and first_grant.employee_id=g.employee_id and first_grant.ordinal=1
 and first_grant.id=case when g.ordinal=1 then g.id else g.predecessor_id end
 join hr_rehire_activation a on a.tenant=first_grant.tenant and a.id=first_grant.id and a.employee_id=g.employee_id
 join hr_employment_episode p on p.tenant=a.tenant and p.rehire_id=a.rehire_id and p.employee_id=a.employee_id;

create table hr_access_grant_episode (
 tenant varchar(80) not null,grant_id varchar(40) not null,employee_id varchar(40) not null,
 episode_number integer not null check(episode_number=2),rehire_id varchar(40) not null,
 episode_start_date date not null,grant_sequence integer not null check(grant_sequence in(1,2)),
 primary key(tenant,grant_id),unique(tenant,employee_id,episode_number,grant_sequence),
 foreign key(tenant,grant_id) references hr_local_access_grant(tenant,id),
 foreign key(tenant,employee_id,episode_number) references hr_employment_episode(tenant,employee_id,number),
 foreign key(tenant,rehire_id) references hr_employment_episode(tenant,rehire_id)
);
insert into hr_access_grant_episode select * from hr_access_grant_episode_source;
do $$ begin
 if exists(select 1 from hr_local_access_grant g left join hr_access_grant_episode p on p.tenant=g.tenant and p.grant_id=g.id where p.grant_id is null)
 then raise exception 'Unreconciled grant episode provenance; repair source history before migration'; end if;
end $$;
create trigger immutable_access_grant_episode before update or delete on hr_access_grant_episode for each row execute function prevent_audit_change();
create function guard_access_grant_episode() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from hr_access_grant_episode_source p where
 p.tenant=new.tenant and p.grant_id=new.grant_id and p.employee_id=new.employee_id
 and p.episode_number=new.episode_number and p.rehire_id=new.rehire_id
 and p.episode_start_date=new.episode_start_date and p.grant_sequence=new.grant_sequence)
 then raise exception 'Exact grant episode provenance required'; end if;
 return new;
end $$;
create trigger guard_access_grant_episode_insert before insert on hr_access_grant_episode for each row execute function guard_access_grant_episode();
create function register_access_grant_episode() returns trigger language plpgsql as $$
begin
 insert into hr_access_grant_episode select * from hr_access_grant_episode_source where tenant=new.tenant and grant_id=new.id;
 if not found then raise exception 'Exact grant episode provenance required'; end if;
 return new;
end $$;
create trigger register_access_grant_episode after insert on hr_local_access_grant for each row execute function register_access_grant_episode();
create view hr_episode_access_current as
 select distinct on(g.tenant,g.employee_id,p.episode_number) g.*,p.episode_number,p.rehire_id,p.episode_start_date,p.grant_sequence
 from hr_local_access_grant g join hr_access_grant_episode p on p.tenant=g.tenant and p.grant_id=g.id
 order by g.tenant,g.employee_id,p.episode_number,p.grant_sequence desc;
