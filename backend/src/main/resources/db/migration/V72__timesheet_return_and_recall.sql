-- Extend lifecycle without altering existing records or applied migrations.
alter table time_sheet drop constraint time_sheet_status_check;
alter table time_sheet add constraint time_sheet_status_check check(status in('DRAFT','SUBMITTED','APPROVED','REJECTED','RETURNED','CORRECTED'));
create or replace function time_sheet_guard() returns trigger language plpgsql as $$
declare e jsonb;d date;seen date[]='{}';total integer=0;n integer;
begin
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/timekeeping/'||new.tenant||'/'||new.employee_id,0));
 if tg_op='UPDATE' and (new.tenant<>old.tenant or new.id<>old.id or new.employee_id<>old.employee_id or new.episode_start<>old.episode_start or new.week<>old.week or new.version<>old.version+1 or not ((old.status in('DRAFT','REJECTED') and new.status in('DRAFT','SUBMITTED') or old.status in('RETURNED','CORRECTED') and new.status in('CORRECTED','SUBMITTED')) or(old.status='SUBMITTED' and new.status in('APPROVED','REJECTED','RETURNED','DRAFT')))) then raise exception 'Invalid timesheet transition';end if;
 if tg_op='INSERT' and (new.status<>'DRAFT' or new.version<>0) then raise exception 'Invalid initial timesheet';end if;
 if new.snapshot->>'id' is distinct from new.id or new.snapshot->>'employeeId' is distinct from new.employee_id or (new.snapshot->>'episodeStart')::date is distinct from new.episode_start or (new.snapshot->>'week')::date is distinct from new.week or new.snapshot->>'status' is distinct from new.status or (new.snapshot->>'version')::numeric is distinct from new.version then raise exception 'Inconsistent timesheet snapshot';end if;
 if jsonb_array_length(new.snapshot->'entries') is distinct from 7 then raise exception 'Exactly seven entries required';end if;
 if not exists(select 1 from pg_timezone_names where name=new.snapshot->>'zone') then raise exception 'Invalid timesheet zone';end if;
 for e in select value from jsonb_array_elements(new.snapshot->'entries') loop
 d=(e->>'date')::date;
 if d is null or d<new.week or d>=new.week+7 or d=any(seen) or (e->>'minutes')::numeric is null or (e->>'minutes')::numeric<>trunc((e->>'minutes')::numeric) then raise exception 'Invalid timesheet date or minutes';end if;
 n=(e->>'minutes')::integer;
 if n<0 or n>1440 or(n>0 and(d<new.episode_start or d>(now() at time zone (new.snapshot->>'zone'))::date or length(trim(coalesce(e->>'code','')))=0 or length(e->>'code')>80)) then raise exception 'Invalid timesheet entry';end if;
 seen=array_append(seen,d);total=total+n;
 end loop;
 if (new.snapshot->>'totalMinutes')::numeric is distinct from total or(new.status not in('DRAFT','CORRECTED') and total=0) then raise exception 'Timesheet total mismatch';end if;
 if tg_op='UPDATE' and old.status='SUBMITTED' and (new.snapshot->'entries'<>old.snapshot->'entries' or new.snapshot->>'zone'<>old.snapshot->>'zone') then raise exception 'Submitted hours are frozen';end if;
 if tg_op='UPDATE' and old.status='RETURNED' and new.status='SUBMITTED' then raise exception 'Returned time requires a saved correction';end if;
 return new;
end $$;
