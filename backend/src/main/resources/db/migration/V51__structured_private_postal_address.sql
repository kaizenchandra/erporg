-- Refuse ambiguous legacy values; do not infer or rewrite structured addresses.
do $$ begin
 if exists(select 1 from hr_employee where address like '@POSTAL/%')
 or exists(select 1 from hr_request where field='address' and(old_value like '@POSTAL/%' or new_value like '@POSTAL/%'))
 or exists(select 1 from hr_version where field='address' and value like '@POSTAL/%')
 or exists(select 1 from hr_audit where field='address' and(old_value like '@POSTAL/%' or new_value like '@POSTAL/%'))
 then raise exception 'Reserved postal address prefix collision; explicit review required'; end if;
end $$;
alter table hr_request alter column old_value type varchar(500),alter column new_value type varchar(500);
alter table hr_version alter column value type varchar(500);
alter table hr_audit alter column old_value type varchar(500),alter column new_value type varchar(500);

create function hr_postal_value_valid(v varchar) returns boolean language plpgsql immutable as $$
declare p text[];
begin
 if v is null then return false;end if;
 if left(v,8)<>'@POSTAL/' then return length(v)<=120;end if;
 if left(v,10)<>E'@POSTAL/1\n' then return false;end if;
 p:=string_to_array(substring(v from 11),E'\n');
 if cardinality(p)<>6 then return false;end if;
 return length(p[1]) between 1 and 120 and length(p[2])<=120
 and length(p[3]) between 1 and 80 and length(p[4])<=80 and length(p[5])<=30 and p[6] ~ '^[A-Z]{2}$'
 and array_to_string(p,'') !~ '[[:cntrl:]]'
 and p[1]=trim(p[1]) and p[2]=trim(p[2]) and p[3]=trim(p[3]) and p[4]=trim(p[4]) and p[5]=trim(p[5]);
end $$;
alter table hr_request add constraint request_address_value_shape check((field='address' and hr_postal_value_valid(old_value) and hr_postal_value_valid(new_value)) or(field<>'address' and length(old_value)<=120 and length(new_value)<=120));
alter table hr_version add constraint version_address_value_shape check((field='address' and hr_postal_value_valid(value)) or(field<>'address' and length(value)<=120));
alter table hr_audit add constraint audit_address_value_shape check((field='address' and hr_postal_value_valid(old_value) and hr_postal_value_valid(new_value)) or(field<>'address' and length(old_value)<=120 and length(new_value)<=120));
