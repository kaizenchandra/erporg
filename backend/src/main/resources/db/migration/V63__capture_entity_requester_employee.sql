-- Strengthen independence with original requester employee, never infer it after reassignment.
-- V62 was exercised without entity writes; do not rewrite its applied checksum.
do $$ begin
 if exists(select 1 from hr_request where field='legalEntityCode') then
  raise exception 'Entity origin upgrade requires reviewed historical provenance; no inferred backfill is permitted';
 end if;
end $$;
create table hr_entity_request_origin(
 tenant varchar(80) not null,request_id varchar(40) primary key references hr_request(id),
 requester_employee_id varchar(40) not null,recorded_at timestamptz not null,
 foreign key(tenant,requester_employee_id) references hr_employee(tenant,id)
);
create function capture_entity_request_origin() returns trigger language plpgsql as $$
declare employee varchar;
begin
 if new.field<>'legalEntityCode' then return new;end if;
 execute format('select employee_id from %I.identity_membership where tenant=$1 and subject=$2 and role=''HR''',tg_table_schema) into employee using new.tenant,new.requested_by;
 if employee is null then raise exception 'Entity assignment requires an HR requester';end if;
 execute format('insert into %I.hr_entity_request_origin(tenant,request_id,requester_employee_id,recorded_at) values($1,$2,$3,$4)',tg_table_schema) using new.tenant,new.id,employee,new.recorded_at;
 return new;
end $$;
create trigger capture_entity_request_origin after insert on hr_request for each row execute function capture_entity_request_origin();
create function guard_entity_origin_source() returns trigger language plpgsql as $$
declare valid boolean;
begin
 execute format('select exists(select 1 from %1$I.hr_request r join %1$I.identity_membership a on a.tenant=r.tenant and a.subject=r.requested_by where r.tenant=$1 and r.id=$2 and r.field=''legalEntityCode'' and r.recorded_at=$3 and a.employee_id=$4 and a.role=''HR'')',tg_table_schema) into valid using new.tenant,new.request_id,new.recorded_at,new.requester_employee_id;
 if valid is distinct from true then raise exception 'Entity origin must match original HR requester';end if;
 return new;
end $$;
create trigger guard_entity_origin_source before insert on hr_entity_request_origin for each row execute function guard_entity_origin_source();
create trigger immutable_entity_origin before update or delete on hr_entity_request_origin for each row execute function prevent_audit_change();
create trigger immutable_entity_origin_truncate before truncate on hr_entity_request_origin for each statement execute function prevent_audit_change();
create or replace function capture_entity_assignment() returns trigger language plpgsql as $$
declare locked_code varchar;valid boolean;started date;
begin
 if new.field<>'legalEntityCode' then return new;end if;
 if current_setting('transaction_isolation') not in('read committed','serializable') then raise exception 'Entity assignment writes require supported isolation';end if;
 if new.value<>'' then
  execute format('select code from %I.hr_legal_entity where tenant=$1 and code=$2 for update',tg_table_schema) into locked_code using new.tenant,new.value;
  if locked_code is null then raise exception 'Entity assignment parent unavailable';end if;
 end if;
 execute format('select %1$I.hr_employment_start(e.tenant,e.id,e.start_date,$4),exists(select 1 from %1$I.hr_request r join %1$I.hr_entity_request_origin o on o.tenant=r.tenant and o.request_id=r.id join %1$I.identity_membership a on a.tenant=r.tenant and a.subject=r.requested_by join %1$I.identity_membership d on d.tenant=r.tenant and d.subject=r.decided_by where r.tenant=$1 and r.id=$2 and r.employee_id=$3 and r.field=''legalEntityCode'' and r.status=''APPROVED'' and r.new_value=$5 and r.effective_date=$4 and r.recorded_at<=$6 and a.role=''HR'' and d.role=''HR'' and a.subject<>d.subject and a.employee_id<>d.employee_id and o.requester_employee_id<>d.employee_id and d.employee_id<>r.employee_id and ($5='''' or (exists(select 1 from %1$I.hr_legal_entity b where b.tenant=$1 and b.code=$5 and b.effective_from<=$4) and not exists(select 1 from %1$I.hr_legal_entity_retirement_request t left join %1$I.hr_legal_entity_retirement_decision x on x.tenant=t.tenant and x.request_id=t.id where t.tenant=$1 and t.code=$5 and (x.request_id is null or x.decision=''APPROVED''))))) from %1$I.hr_employee e where e.tenant=$1 and e.id=$3',tg_table_schema)
 into started,valid using new.tenant,new.request_id,new.employee_id,new.effective_date,new.value,new.recorded_at;
 if valid is distinct from true or started is null or new.effective_date<started or new.effective_date<(new.recorded_at at time zone 'UTC')::date or new.effective_date>((new.recorded_at at time zone 'UTC')::date+interval '1 year') then raise exception 'Entity assignment requires current independently approved reference and episode';end if;
 execute format('insert into %I.hr_legal_entity_assignment(tenant,employee_id,episode_start,entity_code,effective_date,recorded_at,request_id,version_id) values($1,$2,$3,$4,$5,$6,$7,$8)',tg_table_schema)
 using new.tenant,new.employee_id,started,nullif(new.value,''),new.effective_date,new.recorded_at,new.request_id,new.id;
 return new;
end $$;
