-- Supplied personal date only. No employee backfill or statutory-age rule.
alter table hr_request drop constraint hr_request_field_check;
alter table hr_request add constraint hr_request_field_check check(field in('phone','name','legalName','birthDate','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone','positionCode','managerId'));
alter table hr_version drop constraint hr_version_field_check;
alter table hr_version add constraint hr_version_field_check check(field in('phone','name','legalName','birthDate','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone','positionCode','managerId'));
create function hr_birth_date_valid(v varchar, moment date, started date, allow_empty boolean) returns boolean language plpgsql immutable as $$
declare d date;
begin
 if allow_empty and v='' then return true;end if;
 if v is null or v !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' or left(v,4)='0000' then return false;end if;
 begin d:=v::date; exception when others then return false;end;
 return to_char(d,'YYYY-MM-DD')=v and d<=moment and d<=started;
end $$;
create function guard_recorded_birth_date() returns trigger language plpgsql as $$
declare started date; moment date; data jsonb;
begin
 if new.field<>'birthDate' then return new;end if;
 select start_date into started from hr_employee where tenant=new.tenant and id=new.employee_id;
 moment:=(new.recorded_at at time zone 'UTC')::date; data:=to_jsonb(new);
 if started is null or moment is null
 or (tg_table_name='hr_version' and not hr_birth_date_valid((data->>'value')::varchar,moment,started,false))
 or (tg_table_name<>'hr_version' and (not hr_birth_date_valid((data->>'old_value')::varchar,moment,started,true) or not hr_birth_date_valid((data->>'new_value')::varchar,moment,started,false)))
 then raise exception 'Recorded birth date must be canonical and precede recording and original employment';end if;
 return new;
end $$;
create trigger birth_date_request_guard before insert or update on hr_request for each row execute function guard_recorded_birth_date();
create trigger birth_date_version_guard before insert or update on hr_version for each row execute function guard_recorded_birth_date();
create trigger birth_date_audit_guard before insert or update on hr_audit for each row execute function guard_recorded_birth_date();
