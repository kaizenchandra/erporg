-- Qualify by the triggering table's schema, independent of the caller's search_path.
-- Preserve V54 history/checksum and every existing personal snapshot.
create or replace function guard_recorded_birth_date() returns trigger language plpgsql as $$
declare started date; moment date; data jsonb; valid boolean;
begin
 if new.field<>'birthDate' then return new;end if;
 moment:=(new.recorded_at at time zone 'UTC')::date; data:=to_jsonb(new);
 execute format(
  'select e.start_date, %1$I.hr_birth_date_valid($3,$4,e.start_date,$5) and %1$I.hr_birth_date_valid($6,$4,e.start_date,false) from %1$I.hr_employee e where e.tenant=$1 and e.id=$2',
  tg_table_schema)
 into started,valid
 using new.tenant,new.employee_id,
  (case when tg_table_name='hr_version' then data->>'value' else data->>'old_value' end)::varchar,
  moment,tg_table_name<>'hr_version',
  (case when tg_table_name='hr_version' then data->>'value' else data->>'new_value' end)::varchar;
 if started is null or moment is null or valid is distinct from true
 then raise exception 'Recorded birth date must be canonical and precede recording and original employment';end if;
 return new;
end $$;
