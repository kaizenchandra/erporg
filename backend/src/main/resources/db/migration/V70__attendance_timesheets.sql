-- Timekeeping owns these records; no cross-module table reads/FKs.
create table time_shift (
 tenant varchar(80) not null,id varchar(40) not null,employee_id varchar(40) not null,episode_start date not null,
 started_at timestamptz not null,ended_at timestamptz,zone varchar(80) not null,
 version bigint not null check(version>=0),reason varchar(500) not null check(length(trim(reason))>0),recorded_at timestamptz not null,
 primary key(tenant,id),check(ended_at is null or (ended_at>started_at and ended_at-started_at<=interval '24 hours'))
);
create unique index time_one_open_shift on time_shift(tenant,employee_id,episode_start) where ended_at is null;
create index time_shift_directory on time_shift(tenant,employee_id,started_at desc,id);
create table time_sheet (
 tenant varchar(80) not null,id varchar(40) not null,employee_id varchar(40) not null,episode_start date not null,week date not null,
 status varchar(20) not null check(status in('DRAFT','SUBMITTED','APPROVED','REJECTED')),version bigint not null check(version>=0),snapshot jsonb not null,
 primary key(tenant,id),unique(tenant,employee_id,episode_start,week),check(extract(isodow from week)=1)
);
create index time_sheet_queue on time_sheet(tenant,status,employee_id,week desc,id);
create table time_costing_entry (
 tenant varchar(80) not null,sheet_id varchar(40) not null,work_date date not null,minutes integer not null check(minutes>0 and minutes<=1440),
 activity_code varchar(80) not null check(length(trim(activity_code))>0),posted_at timestamptz not null,
 primary key(tenant,sheet_id,work_date),foreign key(tenant,sheet_id) references time_sheet(tenant,id)
);
create table time_command (
 tenant varchar(80) not null,subject varchar(120) not null,command_key varchar(80) not null,fingerprint char(64) not null,result jsonb not null,
 primary key(tenant,subject,command_key)
);
create table time_audit (
 id bigint generated always as identity primary key,tenant varchar(80) not null,employee_id varchar(40) not null,entity_id varchar(40) not null,
 action varchar(30) not null,actor varchar(120) not null,reason varchar(500) not null,before_snapshot jsonb not null,after_snapshot jsonb not null,
 recorded_at timestamptz not null,correlation_id varchar(80) not null
);
create index time_audit_directory on time_audit(tenant,employee_id,id desc);
create function time_shift_guard() returns trigger language plpgsql as $$
begin
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/timekeeping/'||new.tenant||'/'||new.employee_id,0));
 if tg_op='UPDATE' and (new.tenant<>old.tenant or new.id<>old.id or new.employee_id<>old.employee_id or new.episode_start<>old.episode_start or new.version<>old.version+1) then raise exception 'Invalid attendance revision';end if;
 if tg_op='INSERT' and new.version<>0 then raise exception 'Invalid attendance initial revision';end if;
 if not exists(select 1 from pg_timezone_names where name=new.zone) or new.started_at>new.recorded_at or new.ended_at>new.recorded_at or (new.started_at at time zone new.zone)::date<new.episode_start then raise exception 'Invalid attendance date or zone';end if;
 if exists(select 1 from time_shift s where s.tenant=new.tenant and s.employee_id=new.employee_id and s.episode_start=new.episode_start and s.id<>new.id and tstzrange(s.started_at,s.ended_at,'[)')&&tstzrange(new.started_at,new.ended_at,'[)')) then raise exception 'Overlapping attendance';end if;
 return new;
end $$;
create trigger time_shift_integrity before insert or update on time_shift for each row execute function time_shift_guard();
create function time_sheet_guard() returns trigger language plpgsql as $$
declare e jsonb;d date;seen date[]='{}';total integer=0;n integer;
begin
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/timekeeping/'||new.tenant||'/'||new.employee_id,0));
 if tg_op='UPDATE' and (new.tenant<>old.tenant or new.id<>old.id or new.employee_id<>old.employee_id or new.episode_start<>old.episode_start or new.week<>old.week or new.version<>old.version+1 or not ((old.status in('DRAFT','REJECTED') and new.status in('DRAFT','SUBMITTED')) or(old.status='SUBMITTED' and new.status in('APPROVED','REJECTED')))) then raise exception 'Invalid timesheet transition';end if;
 if tg_op='INSERT' and (new.status<>'DRAFT' or new.version<>0) then raise exception 'Invalid initial timesheet';end if;
 if new.snapshot->>'id' is distinct from new.id or new.snapshot->>'employeeId' is distinct from new.employee_id or (new.snapshot->>'episodeStart')::date is distinct from new.episode_start or (new.snapshot->>'week')::date is distinct from new.week or new.snapshot->>'status' is distinct from new.status or (new.snapshot->>'version')::numeric is distinct from new.version then raise exception 'Inconsistent timesheet snapshot';end if;
 if jsonb_array_length(new.snapshot->'entries') is distinct from 7 then raise exception 'Exactly seven entries required';end if;
 if not exists(select 1 from pg_timezone_names where name=new.snapshot->>'zone') then raise exception 'Invalid timesheet zone';end if;
 for e in select value from jsonb_array_elements(new.snapshot->'entries') loop
 d=(e->>'date')::date;
 if d is null or d<new.week or d>=new.week+7 or d=any(seen) or (e->>'minutes')::numeric is null or (e->>'minutes')::numeric<>trunc((e->>'minutes')::numeric) then raise exception 'Invalid timesheet date or minutes';end if;
 n=(e->>'minutes')::integer;
 if n<0 or n>1440 or(n>0 and(d<new.episode_start or length(trim(coalesce(e->>'code','')))=0 or length(e->>'code')>80)) then raise exception 'Invalid timesheet entry';end if;
 seen=array_append(seen,d);total=total+n;
 end loop;
 if (new.snapshot->>'totalMinutes')::numeric is distinct from total or(new.status<>'DRAFT' and total=0) then raise exception 'Timesheet total mismatch';end if;
 if tg_op='UPDATE' and old.status='SUBMITTED' and (new.snapshot->'entries'<>old.snapshot->'entries' or new.snapshot->>'zone'<>old.snapshot->>'zone') then raise exception 'Submitted hours are frozen';end if;
 return new;
end $$;
create trigger time_sheet_integrity before insert or update on time_sheet for each row execute function time_sheet_guard();
create function time_allocation_guard() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from time_sheet s cross join lateral jsonb_array_elements(s.snapshot->'entries') e where s.tenant=new.tenant and s.id=new.sheet_id and s.status='APPROVED' and(e->>'date')::date=new.work_date and(e->>'minutes')::integer=new.minutes and e->>'code'=new.activity_code) then raise exception 'Allocation must match approved hours';end if;return new;
end $$;
create trigger time_allocation_integrity before insert on time_costing_entry for each row execute function time_allocation_guard();
create function time_allocation_reconcile() returns trigger language plpgsql as $$
begin
 if new.status='APPROVED' and (select coalesce(sum(minutes),0) from time_costing_entry where tenant=new.tenant and sheet_id=new.id)<>(new.snapshot->>'totalMinutes')::integer then raise exception 'Approved hours do not reconcile';end if;return new;
end $$;
create constraint trigger time_allocation_balanced after insert or update on time_sheet deferrable initially deferred for each row execute function time_allocation_reconcile();
create trigger time_audit_immutable before update or delete on time_audit for each row execute function prevent_audit_change();
create trigger time_command_immutable before update or delete on time_command for each row execute function prevent_audit_change();
create trigger time_costing_immutable before update or delete on time_costing_entry for each row execute function prevent_audit_change();
create trigger time_sheet_no_delete before delete on time_sheet for each row execute function prevent_audit_change();
create trigger time_shift_no_delete before delete on time_shift for each row execute function prevent_audit_change();
create trigger time_audit_no_truncate before truncate on time_audit for each statement execute function prevent_audit_change();
create trigger time_command_no_truncate before truncate on time_command for each statement execute function prevent_audit_change();
create trigger time_costing_no_truncate before truncate on time_costing_entry for each statement execute function prevent_audit_change();
create trigger time_sheet_no_truncate before truncate on time_sheet for each statement execute function prevent_audit_change();
create trigger time_shift_no_truncate before truncate on time_shift for each statement execute function prevent_audit_change();
