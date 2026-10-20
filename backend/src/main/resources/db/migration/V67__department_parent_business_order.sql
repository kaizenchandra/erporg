-- Prevent clock regression/backdating from introducing a later-date cycle on another branch.
create function guard_department_parent_business_order() returns trigger language plpgsql as $$
begin
 perform pg_advisory_xact_lock(hashtextextended('department-hierarchy/'||NEW.tenant,0));
 if exists(select 1 from hr_department_parent_change where tenant=NEW.tenant and effective_date>NEW.effective_date) then
  raise exception 'Hierarchy contains a later business date';
 end if;
 return NEW;
end $$;
create trigger guard_department_parent_business_order before insert on hr_department_parent_change for each row execute function guard_department_parent_business_order();
