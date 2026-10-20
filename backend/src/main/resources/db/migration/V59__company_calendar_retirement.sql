-- Terminal retirement of unassigned company policy; no source rewrite or inferred retirement.
create table hr_work_calendar_retirement(
 tenant varchar(80) not null,code varchar(30) not null,
 calendar_version integer not null check(calendar_version between 0 and 20),
 effective_date date not null,created_by varchar(120) not null check(length(trim(created_by))>0),
 reason varchar(500) not null check(length(trim(reason)) between 1 and 500 and reason=trim(reason) and reason !~ '[[:cntrl:]]'),
 recorded_at timestamptz not null,correlation_id varchar(120) not null check(length(trim(correlation_id))>0),
 idempotency_key varchar(80) not null check(idempotency_key ~ '^[a-zA-Z0-9-]{8,80}$'),
 primary key(tenant,code),unique(tenant,created_by,idempotency_key),
 foreign key(tenant,code) references hr_work_calendar(tenant,code),
 check(effective_date >=(recorded_at at time zone 'UTC')::date and effective_date<=((recorded_at at time zone 'UTC')::date+interval '1 year'))
);
create function guard_calendar_retirement() returns trigger language plpgsql as $$
declare locked_code varchar;first_date date;last_date date;base_at timestamptz;latest integer;prev_date date;prev_at timestamptz;
begin
 if current_setting('transaction_isolation') not in('read committed','serializable') then raise exception 'Calendar policy writes require supported isolation';end if;
 execute format('select code from %I.hr_work_calendar where tenant=$1 and code=$2 for update',tg_table_schema) into locked_code using new.tenant,new.code;
 execute format('select b.effective_from,b.effective_until,b.recorded_at,coalesce(p.ordinal,0),p.effective_date,p.recorded_at from %1$I.hr_work_calendar b left join lateral(select ordinal,effective_date,recorded_at from %1$I.hr_work_calendar_revision where tenant=b.tenant and code=b.code order by ordinal desc limit 1) p on true where b.tenant=$1 and b.code=$2',tg_table_schema)
 into first_date,last_date,base_at,latest,prev_date,prev_at using new.tenant,new.code;
 if locked_code is null or first_date is null or new.effective_date<first_date or new.effective_date>last_date or new.recorded_at<base_at or new.calendar_version<>latest
 or(prev_date is not null and(new.effective_date<=prev_date or new.recorded_at<prev_at))
 then raise exception 'Calendar retirement must preserve version, validity and chronology';end if;
 return new;
end $$;
create trigger guard_calendar_retirement before insert on hr_work_calendar_retirement for each row execute function guard_calendar_retirement();
create trigger immutable_calendar_retirement before update or delete on hr_work_calendar_retirement for each row execute function prevent_audit_change();
create trigger immutable_calendar_retirement_truncate before truncate on hr_work_calendar_retirement for each statement execute function prevent_audit_change();
create or replace function guard_calendar_revision() returns trigger language plpgsql as $$
declare locked_code varchar;first_date date;last_date date;base_at timestamptz;prev_date date;prev_at timestamptz;valid boolean;retired boolean;
begin
 if current_setting('transaction_isolation') not in('read committed','serializable') then raise exception 'Calendar policy writes require supported isolation';end if;
 execute format('select code from %I.hr_work_calendar where tenant=$1 and code=$2 for update',tg_table_schema) into locked_code using new.tenant,new.code;
 execute format('select b.effective_from,b.effective_until,b.recorded_at,p.effective_date,p.recorded_at,%1$I.hr_calendar_closures_valid($4,$5,b.effective_until),exists(select 1 from %1$I.hr_work_calendar_retirement t where t.tenant=b.tenant and t.code=b.code) from %1$I.hr_work_calendar b left join %1$I.hr_work_calendar_revision p on p.tenant=b.tenant and p.code=b.code and p.ordinal=$3-1 where b.tenant=$1 and b.code=$2',tg_table_schema)
 into first_date,last_date,base_at,prev_date,prev_at,valid,retired using new.tenant,new.code,new.ordinal,new.closed_dates,new.effective_date;
 if locked_code is null or first_date is null or new.effective_date<first_date or new.effective_date>last_date or new.recorded_at<base_at or valid is distinct from true or retired is distinct from false
 or(new.ordinal>1 and(prev_date is null or new.effective_date<=prev_date or new.recorded_at<prev_at))
 then raise exception 'Calendar amendment must preserve sequence, validity, chronology and retirement';end if;
 return new;
end $$;
