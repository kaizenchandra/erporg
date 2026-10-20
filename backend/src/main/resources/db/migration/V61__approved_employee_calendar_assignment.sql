-- Episode-owned company calendar references only. No backfill or leave/payroll activation.
alter table hr_request drop constraint hr_request_field_check;
alter table hr_request add constraint hr_request_field_check check(field in('phone','name','legalName','birthDate','personalEmail','calendarCode','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone','positionCode','managerId'));
alter table hr_version drop constraint hr_version_field_check;
alter table hr_version add constraint hr_version_field_check check(field in('phone','name','legalName','birthDate','personalEmail','calendarCode','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone','positionCode','managerId'));
create table hr_calendar_request_policy(
 tenant varchar(80) not null,request_id varchar(40) primary key references hr_request(id),
 calendar_code varchar(30),policy_version integer check(policy_version between 0 and 20),recorded_at timestamptz not null,
 foreign key(tenant,calendar_code) references hr_work_calendar(tenant,code),
 check((calendar_code is null)=(policy_version is null))
);
create table hr_calendar_assignment(
 tenant varchar(80) not null,employee_id varchar(40) not null,episode_start date not null,
 calendar_code varchar(30),effective_date date not null,recorded_at timestamptz not null,
 request_id varchar(40) primary key references hr_request(id),version_id bigint not null unique references hr_version(id),
 foreign key(tenant,employee_id) references hr_employee(tenant,id),foreign key(tenant,calendar_code) references hr_work_calendar(tenant,code),
 check(effective_date>=episode_start)
);
create index hr_calendar_assignment_selection on hr_calendar_assignment(tenant,employee_id,episode_start,effective_date desc,recorded_at desc,version_id desc);
create index hr_calendar_assignment_policy_use on hr_calendar_assignment(tenant,calendar_code) where calendar_code is not null;
create function capture_calendar_request_policy() returns trigger language plpgsql as $$
declare locked_code varchar;v integer;
begin
 if new.field<>'calendarCode' then return new;end if;
 if current_setting('transaction_isolation') not in('read committed','serializable') then raise exception 'Calendar assignment writes require supported isolation';end if;
 if new.new_value<>'' and new.new_value !~ '^[A-Z0-9-]{2,30}$' then raise exception 'Canonical calendar assignment required';end if;
 if new.old_value<>'' and new.old_value !~ '^[A-Z0-9-]{2,30}$' then raise exception 'Canonical prior calendar required';end if;
 if new.new_value<>'' then
  execute format('select code from %I.hr_work_calendar where tenant=$1 and code=$2 for update',tg_table_schema) into locked_code using new.tenant,new.new_value;
  if locked_code is null then raise exception 'Calendar assignment parent unavailable';end if;
  execute format('select count(*) from %I.hr_work_calendar_revision where tenant=$1 and code=$2',tg_table_schema) into v using new.tenant,new.new_value;
 end if;
 execute format('insert into %I.hr_calendar_request_policy(tenant,request_id,calendar_code,policy_version,recorded_at) values($1,$2,$3,$4,$5)',tg_table_schema)
 using new.tenant,new.id,nullif(new.new_value,''),v,new.recorded_at;
 return new;
end $$;
create trigger capture_calendar_request_policy after insert on hr_request for each row execute function capture_calendar_request_policy();
create function guard_calendar_request_snapshot() returns trigger language plpgsql as $$
declare valid boolean;
begin
 execute format('select exists(select 1 from %1$I.hr_request r where r.tenant=$1 and r.id=$2 and r.field=''calendarCode'' and nullif(r.new_value,'''') is not distinct from $3 and r.recorded_at=$4)',tg_table_schema)
 into valid using new.tenant,new.request_id,new.calendar_code,new.recorded_at;
 if valid is distinct from true then raise exception 'Calendar request snapshot must match its source';end if;
 return new;
end $$;
create trigger guard_calendar_request_snapshot before insert on hr_calendar_request_policy for each row execute function guard_calendar_request_snapshot();
create function capture_calendar_assignment() returns trigger language plpgsql as $$
declare locked_code varchar;valid boolean;started date;
begin
 if new.field<>'calendarCode' then return new;end if;
 if current_setting('transaction_isolation') not in('read committed','serializable') then raise exception 'Calendar assignment writes require supported isolation';end if;
 if new.value<>'' then
  execute format('select code from %I.hr_work_calendar where tenant=$1 and code=$2 for update',tg_table_schema) into locked_code using new.tenant,new.value;
  if locked_code is null then raise exception 'Calendar assignment parent unavailable';end if;
 end if;
 execute format('select %1$I.hr_employment_start(e.tenant,e.id,e.start_date,$4),exists(select 1 from %1$I.hr_request r join %1$I.hr_calendar_request_policy p on p.tenant=r.tenant and p.request_id=r.id join %1$I.identity_membership a on a.tenant=r.tenant and a.subject=r.requested_by join %1$I.identity_membership d on d.tenant=r.tenant and d.subject=r.decided_by where r.tenant=$1 and r.id=$2 and r.employee_id=$3 and r.field=''calendarCode'' and r.status=''APPROVED'' and r.new_value=$5 and r.effective_date=$4 and r.recorded_at<=$6 and a.role=''HR'' and d.role=''HR'' and a.subject<>d.subject and a.employee_id<>d.employee_id and d.employee_id<>r.employee_id and ((p.calendar_code is null and $5='''') or(p.calendar_code=$5 and p.policy_version=(select count(*) from %1$I.hr_work_calendar_revision v where v.tenant=$1 and v.code=$5) and exists(select 1 from %1$I.hr_work_calendar b where b.tenant=$1 and b.code=$5 and $4 between b.effective_from and b.effective_until) and not exists(select 1 from %1$I.hr_work_calendar_retirement t where t.tenant=$1 and t.code=$5)))) from %1$I.hr_employee e where e.tenant=$1 and e.id=$3',tg_table_schema)
 into started,valid using new.tenant,new.request_id,new.employee_id,new.effective_date,new.value,new.recorded_at;
 if valid is distinct from true or started is null or new.effective_date<started or new.effective_date<(new.recorded_at at time zone 'UTC')::date or new.effective_date>((new.recorded_at at time zone 'UTC')::date+interval '1 year') then raise exception 'Calendar assignment requires current independently approved policy and episode';end if;
 execute format('insert into %I.hr_calendar_assignment(tenant,employee_id,episode_start,calendar_code,effective_date,recorded_at,request_id,version_id) values($1,$2,$3,$4,$5,$6,$7,$8)',tg_table_schema)
 using new.tenant,new.employee_id,started,nullif(new.value,''),new.effective_date,new.recorded_at,new.request_id,new.id;
 return new;
end $$;
create trigger capture_calendar_assignment after insert on hr_version for each row execute function capture_calendar_assignment();
create function guard_calendar_assignment_source() returns trigger language plpgsql as $$
declare valid boolean;
begin
 execute format('select exists(select 1 from %1$I.hr_version v where v.tenant=$1 and v.id=$2 and v.request_id=$3 and v.employee_id=$4 and v.field=''calendarCode'' and nullif(v.value,'''') is not distinct from $5 and v.effective_date=$6 and v.recorded_at=$7)',tg_table_schema)
 into valid using new.tenant,new.version_id,new.request_id,new.employee_id,new.calendar_code,new.effective_date,new.recorded_at;
 if valid is distinct from true then raise exception 'Calendar assignment ledger must match approved version';end if;
 return new;
end $$;
create trigger guard_calendar_assignment_source before insert on hr_calendar_assignment for each row execute function guard_calendar_assignment_source();
create function freeze_assigned_calendar_policy() returns trigger language plpgsql as $$
declare locked_code varchar;used boolean;
begin
 if current_setting('transaction_isolation') not in('read committed','serializable') then raise exception 'Calendar policy writes require supported isolation';end if;
 execute format('select code from %I.hr_work_calendar where tenant=$1 and code=$2 for update',tg_table_schema) into locked_code using new.tenant,new.code;
 execute format('select exists(select 1 from %I.hr_calendar_assignment where tenant=$1 and calendar_code=$2)',tg_table_schema) into used using new.tenant,new.code;
 if used then raise exception 'Approved assignment history freezes calendar policy';end if;
 return new;
end $$;
create trigger assigned_policy_amendment_guard before insert on hr_work_calendar_revision for each row execute function freeze_assigned_calendar_policy();
create trigger assigned_policy_retirement_guard before insert on hr_work_calendar_retirement for each row execute function freeze_assigned_calendar_policy();
create function immutable_calendar_version() returns trigger language plpgsql as $$
begin
 if old.field='calendarCode' or (tg_op='UPDATE' and new.field='calendarCode') then raise exception 'Calendar assignment versions are append only';end if;
 return case when tg_op='DELETE' then old else new end;
end $$;
create trigger immutable_calendar_version before update or delete on hr_version for each row execute function immutable_calendar_version();
create trigger immutable_calendar_request_policy before update or delete on hr_calendar_request_policy for each row execute function prevent_audit_change();
create trigger immutable_calendar_request_policy_truncate before truncate on hr_calendar_request_policy for each statement execute function prevent_audit_change();
create trigger immutable_calendar_assignment before update or delete on hr_calendar_assignment for each row execute function prevent_audit_change();
create trigger immutable_calendar_assignment_truncate before truncate on hr_calendar_assignment for each statement execute function prevent_audit_change();
