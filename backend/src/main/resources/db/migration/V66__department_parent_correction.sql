-- Preserve declared hierarchy; current corrections are append-only, date-effective configuration.
create table hr_department_parent_change (
 tenant varchar(80) not null, code varchar(30) not null,
 version bigint not null check(version>0), old_parent_code varchar(30), parent_code varchar(30),
 effective_date date not null, recorded_at timestamptz not null, actor varchar(120) not null,
 reason varchar(500) not null check(length(trim(reason)) between 1 and 500 and reason !~ '[[:cntrl:]]'),
 idempotency_key varchar(80) not null check(idempotency_key ~ '^[a-zA-Z0-9-]{8,80}$'), correlation_id varchar(80) not null check(length(correlation_id)>0),
 primary key(tenant,code,version), unique(tenant,actor,idempotency_key),
 foreign key(tenant,code) references hr_department(tenant,code),
 foreign key(tenant,old_parent_code) references hr_department(tenant,code),
 foreign key(tenant,parent_code) references hr_department(tenant,code),
 check(parent_code is null or parent_code<>code),
 check(parent_code is distinct from old_parent_code),
 check(effective_date=(recorded_at at time zone 'UTC')::date)
);
create index hr_department_parent_effective on hr_department_parent_change(tenant,code,effective_date desc,version desc);
create trigger immutable_department_parent_change before update or delete on hr_department_parent_change for each row execute function prevent_audit_change();

create function hr_department_parent(t varchar,c varchar,d date) returns varchar language sql stable as $$
 select v.parent_code from (
  select a.parent_code,a.version from hr_department_parent_change a where a.tenant=t and a.code=c and a.effective_date<=d
  union all select b.parent_code,0::bigint from hr_department b where b.tenant=t and b.code=c
 ) v order by v.version desc limit 1
$$;

-- A hierarchy lock also covers inserts from older application writers.
create function guard_department_declaration() returns trigger language plpgsql as $$
declare p date;
begin
 if TG_OP<>'INSERT' then raise exception 'Department declarations are immutable'; end if;
 perform pg_advisory_xact_lock(hashtextextended('department-hierarchy/'||NEW.tenant,0));
 if NEW.parent_code is not null then
  select effective_from into p from hr_department where tenant=NEW.tenant and code=NEW.parent_code;
  if p is null or p>NEW.effective_from then raise exception 'Department parent must be available by declaration date'; end if;
 end if;
 return NEW;
end $$;
create trigger guard_department_declaration before insert or update or delete on hr_department for each row execute function guard_department_declaration();

create function guard_department_parent_change() returns trigger language plpgsql as $$
declare last_version bigint; last_date date; target_date date; parent_date date; cursor_code varchar; visited varchar[];
begin
 perform pg_advisory_xact_lock(hashtextextended('department-hierarchy/'||NEW.tenant,0));
 select effective_from into target_date from hr_department where tenant=NEW.tenant and code=NEW.code;
 select version,effective_date into last_version,last_date from hr_department_parent_change where tenant=NEW.tenant and code=NEW.code order by version desc limit 1;
 if target_date is null or target_date>NEW.effective_date or NEW.version<>coalesce(last_version,0)+1 or NEW.effective_date<coalesce(last_date,NEW.effective_date) then
  raise exception 'Invalid department parent revision or effective date';
 end if;
 if NEW.old_parent_code is distinct from hr_department_parent(NEW.tenant,NEW.code,NEW.effective_date) then raise exception 'Department old parent does not match'; end if;
 visited:=array[NEW.code]; cursor_code:=NEW.parent_code;
 while cursor_code is not null loop
  if cursor_code=any(visited) then raise exception 'Department hierarchy cycle'; end if;
  visited:=array_append(visited,cursor_code);
  select effective_from into parent_date from hr_department where tenant=NEW.tenant and code=cursor_code;
  if parent_date is null or parent_date>NEW.effective_date then raise exception 'Department parent must be currently effective'; end if;
  cursor_code:=hr_department_parent(NEW.tenant,cursor_code,NEW.effective_date);
 end loop;
 return NEW;
end $$;
create trigger guard_department_parent_change before insert on hr_department_parent_change for each row execute function guard_department_parent_change();

-- Fail an unsafe upgrade instead of silently repairing historical declarations.
do $$ begin
 if exists(select 1 from hr_department d join hr_department p on p.tenant=d.tenant and p.code=d.parent_code where p.effective_from>d.effective_from) then
  raise exception 'Department parent chronology preflight failed';
 end if;
 if exists(
  with recursive chain as (
   select tenant,code,parent_code,array[code]::varchar[] path,false cycle from hr_department
   union all
   select c.tenant,p.code,p.parent_code,c.path||p.code,p.code=any(c.path) from chain c join hr_department p on p.tenant=c.tenant and p.code=c.parent_code where not c.cycle
  ) select 1 from chain where cycle
 ) then raise exception 'Department parent cycle preflight failed'; end if;
end $$;
