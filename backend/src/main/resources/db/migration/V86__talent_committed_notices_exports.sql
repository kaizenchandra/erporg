-- In-app delivery is committed atomically with the business event. No email delivery is asserted.
create table tal_notice(tenant varchar(80) not null,id varchar(40) not null,event_id varchar(40) not null,employee_id varchar(40) not null,snapshot jsonb not null,primary key(tenant,id),unique(tenant,event_id,employee_id),foreign key(tenant,event_id) references tal_audit(tenant,id),check(snapshot->>'id'=id and snapshot->>'eventId'=event_id and snapshot->>'employeeId'=employee_id and snapshot ?& array['kind','recordId','status','recordedAt']));
create index tal_notice_inbox on tal_notice(tenant,employee_id,id);
create table tal_export_receipt(tenant varchar(80) not null,id varchar(40) not null,actor varchar(120) not null,employee_id varchar(40) not null,kind varchar(30) not null,page integer not null check(page between 0 and 10000),row_count integer not null check(row_count between 0 and 10),recorded_at timestamptz not null,correlation_id varchar(80) not null,primary key(tenant,id));
create function tal_notice_guard() returns trigger language plpgsql as $$
declare event jsonb;
begin
 select snapshot into strict event from tal_audit where tenant=new.tenant and id=new.event_id;
 if new.snapshot->>'kind'<>event->>'kind' or new.snapshot->>'recordId'<>event->>'recordId' or new.snapshot->>'status'<>(event->>'after')::jsonb->'meta'->>'status' then raise exception 'Notice requires its exact committed talent event';end if;
 return new;
end $$;
create trigger tal_notice_integrity before insert on tal_notice for each row execute function tal_notice_guard();
do $$ declare t text;begin
 foreach t in array array['tal_notice','tal_export_receipt'] loop
 execute format('create trigger %I before update or delete on %I for each row execute function prevent_audit_change()',t||'_immutable',t);
 execute format('create trigger %I before truncate on %I for each statement execute function prevent_audit_change()',t||'_no_truncate',t);
 end loop;
 execute format('alter function tal_notice_guard() set search_path to %I,pg_catalog',current_schema());
end $$;
