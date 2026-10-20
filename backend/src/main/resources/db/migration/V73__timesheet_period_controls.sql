-- Append-only tenant-owned weekly controls. Historical policy revisions are never overwritten.
create table time_period (
 tenant varchar(80) not null, week date not null, version bigint not null check(version between 1 and 1000),
 actor varchar(120) not null, command_key varchar(80) not null check(command_key ~ '^[a-zA-Z0-9-]{8,80}$'), snapshot jsonb not null,
 primary key(tenant,week,version), unique(tenant,actor,command_key), check(extract(isodow from week)=1)
);
create function time_period_guard() returns trigger language plpgsql as $$
declare p jsonb=new.snapshot; previous bigint;
begin
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/timekeeping/'||new.tenant||'/period/'||new.week,0));
 select coalesce(max(version),0) into previous from time_period where tenant=new.tenant and week=new.week;
 if new.version<>previous+1 then raise exception 'Period revision conflict';end if;
 if (p->>'week')::date is distinct from new.week or (p->>'version')::numeric is distinct from new.version or p->>'actor' is distinct from new.actor or p->>'commandKey' is distinct from new.command_key then raise exception 'Period snapshot mismatch';end if;
 if p->>'deadline' is null or (p->>'deadline')::timestamptz < (new.week::timestamp at time zone 'UTC') or
    (p->>'dailyLimit')::numeric is null or (p->>'dailyLimit')::numeric<>trunc((p->>'dailyLimit')::numeric) or
    (p->>'dailyLimit')::numeric not between 1 and 1440 or
    (p->>'weeklyLimit')::numeric is null or (p->>'weeklyLimit')::numeric<>trunc((p->>'weeklyLimit')::numeric) or
    (p->>'weeklyLimit')::numeric not between (p->>'dailyLimit')::numeric and 10080 or
    jsonb_typeof(p->'closed') is distinct from 'boolean' or jsonb_typeof(p->'recallAllowed') is distinct from 'boolean' or
    length(trim(coalesce(p->>'reason','')))=0 or length(p->>'reason')>500 or p->>'recordedAt' is null then raise exception 'Invalid period controls';end if;
 return new;
end $$;
create trigger time_period_integrity before insert on time_period for each row execute function time_period_guard();
create trigger time_period_immutable before update or delete on time_period for each row execute function prevent_audit_change();
create trigger time_period_no_truncate before truncate on time_period for each statement execute function prevent_audit_change();
create function time_sheet_period_guard() returns trigger language plpgsql as $$
declare p jsonb;
begin
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/timekeeping/'||new.tenant||'/period/'||new.week,0));
 select snapshot into p from time_period where tenant=new.tenant and week=new.week order by version desc limit 1;
 if p is null then return new;end if;
 if tg_op='UPDATE' and old.status='SUBMITTED' and new.status='DRAFT' then
   if not (p->>'recallAllowed')::boolean then raise exception 'Recall disabled';end if;
 elsif new.status in('DRAFT','CORRECTED','SUBMITTED','APPROVED') then
   if (p->>'closed')::boolean then raise exception 'Reporting period closed';end if;
   if new.status='SUBMITTED' and now()>=(p->>'deadline')::timestamptz then raise exception 'Submission deadline passed';end if;
   if (new.snapshot->>'totalMinutes')::integer>(p->>'weeklyLimit')::integer or exists(select 1 from jsonb_array_elements(new.snapshot->'entries') e where (e->>'minutes')::integer>(p->>'dailyLimit')::integer) then raise exception 'Period hours limit exceeded';end if;
 end if;
 return new;
end $$;
create trigger time_sheet_period_integrity before insert or update on time_sheet for each row execute function time_sheet_period_guard();
