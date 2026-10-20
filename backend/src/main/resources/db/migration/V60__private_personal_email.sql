-- Supplied private contact snapshots, no backfill or identity/provider effect.
alter table hr_request drop constraint hr_request_field_check;
alter table hr_request add constraint hr_request_field_check check(field in('phone','name','legalName','birthDate','personalEmail','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone','positionCode','managerId'));
alter table hr_version drop constraint hr_version_field_check;
alter table hr_version add constraint hr_version_field_check check(field in('phone','name','legalName','birthDate','personalEmail','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone','positionCode','managerId'));
create function hr_personal_email_valid(v varchar) returns boolean language sql immutable as $fn$
 select v is not null and (v='' or (
 length(v)<=120 and position('@' in v) between 2 and 65
 and v ~ $pattern$^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$$pattern$
 ));
$fn$;
create function guard_personal_email() returns trigger language plpgsql as $$
declare data jsonb; valid boolean;
begin
 if new.field<>'personalEmail' then return new;end if;
 data:=to_jsonb(new);
 execute format('select %1$I.hr_personal_email_valid($1) and %1$I.hr_personal_email_valid($2)',tg_table_schema)
 into valid using
 (case when tg_table_name='hr_version' then data->>'value' else data->>'old_value' end)::varchar,
 (case when tg_table_name='hr_version' then data->>'value' else data->>'new_value' end)::varchar;
 if valid is distinct from true then raise exception 'Personal email snapshot must be supported ASCII contact data';end if;
 return new;
end $$;
create trigger personal_email_request_guard before insert or update on hr_request for each row execute function guard_personal_email();
create trigger personal_email_version_guard before insert or update on hr_version for each row execute function guard_personal_email();
create trigger personal_email_audit_guard before insert or update on hr_audit for each row execute function guard_personal_email();
