-- Immutable dated policy replacements; no base rewrite, assignment or inferred rules.
create table hr_work_calendar_revision(
 tenant varchar(80) not null,code varchar(30) not null,
 ordinal integer not null check(ordinal between 1 and 20),effective_date date not null,
 working_days smallint[] not null check(hr_calendar_days_valid(working_days)),closed_dates date[] not null,
 created_by varchar(120) not null check(length(trim(created_by))>0),
 reason varchar(500) not null check(length(trim(reason)) between 1 and 500 and reason=trim(reason) and reason !~ '[[:cntrl:]]'),
 recorded_at timestamptz not null,correlation_id varchar(120) not null check(length(trim(correlation_id))>0),
 idempotency_key varchar(80) not null check(idempotency_key ~ '^[a-zA-Z0-9-]{8,80}$'),
 primary key(tenant,code,ordinal),unique(tenant,code,effective_date),unique(tenant,created_by,idempotency_key),
 foreign key(tenant,code) references hr_work_calendar(tenant,code),
 check(effective_date >= (recorded_at at time zone 'UTC')::date and effective_date<=((recorded_at at time zone 'UTC')::date+interval '1 year'))
);
create function guard_calendar_revision() returns trigger language plpgsql as $$
declare first_date date;last_date date;base_at timestamptz;prev_date date;prev_at timestamptz;valid boolean;
begin
 execute format('select b.effective_from,b.effective_until,b.recorded_at,p.effective_date,p.recorded_at,%1$I.hr_calendar_closures_valid($4,$5,b.effective_until) from %1$I.hr_work_calendar b left join %1$I.hr_work_calendar_revision p on p.tenant=b.tenant and p.code=b.code and p.ordinal=$3-1 where b.tenant=$1 and b.code=$2',tg_table_schema)
 into first_date,last_date,base_at,prev_date,prev_at using new.tenant,new.code,new.ordinal,new.closed_dates,new.effective_date;
 if first_date is null or new.effective_date<first_date or new.effective_date>last_date or new.recorded_at<base_at or valid is distinct from true
 or(new.ordinal>1 and(prev_date is null or new.effective_date<=prev_date or new.recorded_at<prev_at))
 then raise exception 'Calendar amendment must preserve sequence, validity and recording chronology';end if;
 return new;
end $$;
create trigger guard_calendar_revision before insert on hr_work_calendar_revision for each row execute function guard_calendar_revision();
create trigger immutable_calendar_revision before update or delete on hr_work_calendar_revision for each row execute function prevent_audit_change();
create trigger immutable_calendar_revision_truncate before truncate on hr_work_calendar_revision for each statement execute function prevent_audit_change();
