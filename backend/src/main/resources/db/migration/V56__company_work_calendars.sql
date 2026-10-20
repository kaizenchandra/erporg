-- Explicit company policy only; no public-holiday or employee/payroll assignment backfill.
create function hr_calendar_days_valid(days smallint[]) returns boolean language sql immutable as $$
 select coalesce(cardinality(days) between 1 and 7 and array_position(days,null) is null and days=array(select distinct d from unnest(days) d order by d) and 1<=all(days) and 7>=all(days),false)
$$;
create function hr_calendar_closures_valid(dates date[],first_date date,last_date date) returns boolean language sql immutable as $$
 select coalesce(cardinality(dates)<=50 and array_position(dates,null) is null and dates=array(select distinct d from unnest(dates) d order by d) and first_date<=all(dates) and last_date>=all(dates),false)
$$;
create table hr_work_calendar(
 tenant varchar(80) not null,
 code varchar(30) not null check(code ~ '^[A-Z0-9-]{2,30}$'),
 name varchar(120) not null check(length(trim(name)) between 1 and 120 and name !~ '[[:cntrl:]]' and name=trim(name)),
 time_zone varchar(80) not null check(time_zone ~ '^[A-Za-z0-9_+/-]{1,80}$'),
 effective_from date not null check(effective_from between date '0001-01-01' and date '9999-12-31'),
 effective_until date not null check(effective_until<=date '9999-12-31' and effective_until>=effective_from and effective_until<effective_from+interval '1 year'),
 working_days smallint[] not null check(hr_calendar_days_valid(working_days)),
 closed_dates date[] not null check(hr_calendar_closures_valid(closed_dates,effective_from,effective_until)),
 created_by varchar(120) not null check(length(trim(created_by))>0),
 reason varchar(500) not null check(length(trim(reason)) between 1 and 500 and reason !~ '[[:cntrl:]]' and reason=trim(reason)),
 recorded_at timestamptz not null,
 correlation_id varchar(120) not null check(length(trim(correlation_id))>0),
 idempotency_key varchar(80) not null check(idempotency_key ~ '^[a-zA-Z0-9-]{8,80}$'),
 primary key(tenant,code),unique(tenant,created_by,idempotency_key),
 check(effective_from >=(recorded_at at time zone 'UTC')::date and effective_from<=((recorded_at at time zone 'UTC')::date+interval '1 year'))
);
create trigger immutable_work_calendar before update or delete on hr_work_calendar for each row execute function prevent_audit_change();
create trigger immutable_work_calendar_truncate before truncate on hr_work_calendar for each statement execute function prevent_audit_change();
