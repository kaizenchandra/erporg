-- Core HR owns this synchronous read projection. It grants no access by itself.
-- Episode 1 remains implicit in hr_employee; later episode writes remain bounded to 2.
create view hr_employment_episode_state as
 select e.tenant,e.id employee_id,1::integer episode_number,e.start_date,
 e.status original_status,null::varchar rehire_id,x.inactive_date,null::date completion_effective_date
 from hr_employee e left join hr_episode_end_current x
 on x.tenant=e.tenant and x.employee_id=e.id and x.episode_number=1 and x.episode_start_date=e.start_date
 union all
 select p.tenant,p.employee_id,p.number,p.start_date,p.status,p.rehire_id,x.inactive_date,c.effective_date
 from hr_employment_episode p left join hr_episode_end_current x
 on x.tenant=p.tenant and x.employee_id=p.employee_id and x.episode_number=p.number and x.episode_start_date=p.start_date
 left join hr_rehire_completion c on c.tenant=p.tenant and c.employee_id=p.employee_id and c.rehire_id=p.rehire_id;

create or replace function hr_employment_status(t varchar,e varchar,original varchar,as_of_date date) returns varchar language sql stable as $$
 select coalesce((select case
 when p.inactive_date<=as_of_date then 'Ended'
 when p.completion_effective_date<=as_of_date then 'Active'
 else p.original_status end
 from hr_employment_episode_state p where p.tenant=t and p.employee_id=e and p.start_date<=as_of_date
 order by p.start_date desc,p.episode_number desc limit 1),original);
$$;

create or replace function hr_employment_start(t varchar,e varchar,original date,as_of_date date) returns date language sql stable as $$
 select coalesce((select p.start_date from hr_employment_episode_state p
 where p.tenant=t and p.employee_id=e and p.start_date<=as_of_date
 order by p.start_date desc,p.episode_number desc limit 1),original);
$$;
