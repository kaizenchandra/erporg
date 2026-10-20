-- Namespace hierarchy serialization to the owning schema as well as tenant.
create or replace function guard_department_declaration() returns trigger language plpgsql as $$
declare p date;
begin
 if TG_OP<>'INSERT' then raise exception 'Department declarations are immutable'; end if;
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/department-hierarchy/'||NEW.tenant,0));
 if NEW.parent_code is not null then
  select effective_from into p from hr_department where tenant=NEW.tenant and code=NEW.parent_code;
  if p is null or p>NEW.effective_from then raise exception 'Department parent must be available by declaration date'; end if;
 end if;
 return NEW;
end $$;
create or replace function guard_department_parent_change() returns trigger language plpgsql as $$
declare last_version bigint; last_date date; target_date date; parent_date date; cursor_code varchar; visited varchar[];
begin
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/department-hierarchy/'||NEW.tenant,0));
 select effective_from into target_date from hr_department where tenant=NEW.tenant and code=NEW.code;
 select version,effective_date into last_version,last_date from hr_department_parent_change where tenant=NEW.tenant and code=NEW.code order by version desc limit 1;
 if target_date is null or target_date>NEW.effective_date or NEW.version<>coalesce(last_version,0)+1 or NEW.effective_date<coalesce(last_date,NEW.effective_date) then
  raise exception 'Invalid department parent revision or effective date';
 end if;
 if NEW.old_parent_code is distinct from hr_department_parent(NEW.tenant,NEW.code,NEW.effective_date) then raise exception 'Department old parent does not match'; end if;
 visited:=array[NEW.code]; cursor_code:=NEW.parent_code;
 while cursor_code is not null loop
  if cursor_code=any(visited) then raise exception 'Department hierarchy cycle'; end if;
  visited:=array_append(visited,cursor_code);
  select effective_from into parent_date from hr_department where tenant=NEW.tenant and code=cursor_code;
  if parent_date is null or parent_date>NEW.effective_date then raise exception 'Department parent must be currently effective'; end if;
  cursor_code:=hr_department_parent(NEW.tenant,cursor_code,NEW.effective_date);
 end loop;
 return NEW;
end $$;
create or replace function guard_department_parent_business_order() returns trigger language plpgsql as $$
begin
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/department-hierarchy/'||NEW.tenant,0));
 if exists(select 1 from hr_department_parent_change where tenant=NEW.tenant and effective_date>NEW.effective_date) then
  raise exception 'Hierarchy contains a later business date';
 end if;
 return NEW;
end $$;
