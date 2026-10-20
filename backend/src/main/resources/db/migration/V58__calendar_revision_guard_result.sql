-- Preserve applied V57 checksum; capture the complete guard validation result.
create or replace function guard_calendar_revision() returns trigger language plpgsql as $$
declare first_date date;last_date date;base_at timestamptz;prev_date date;prev_at timestamptz;valid boolean;
begin
 execute format('select b.effective_from,b.effective_until,b.recorded_at,p.effective_date,p.recorded_at,%1$I.hr_calendar_closures_valid($4,$5,b.effective_until) from %1$I.hr_work_calendar b left join %1$I.hr_work_calendar_revision p on p.tenant=b.tenant and p.code=b.code and p.ordinal=$3-1 where b.tenant=$1 and b.code=$2',tg_table_schema)
 into first_date,last_date,base_at,prev_date,prev_at,valid using new.tenant,new.code,new.ordinal,new.closed_dates,new.effective_date;
 if first_date is null or new.effective_date<first_date or new.effective_date>last_date or new.recorded_at<base_at or valid is distinct from true
 or(new.ordinal>1 and(prev_date is null or new.effective_date<=prev_date or new.recorded_at<prev_at))
 then raise exception 'Calendar amendment must preserve sequence, validity and recording chronology';end if;
 return new;
end $$;
