-- No email/broker: committed in-app delivery is the authoritative outcome.
create table tal_reminder_policy(tenant varchar(80) not null,id varchar(40) not null,version bigint not null check(version>=1),snapshot jsonb not null,primary key(tenant,id),unique(tenant,version),check(snapshot ?& array['id','version','enabled','thresholdMinutes','frequencyMinutes','owner','ownerEmployee','reason','recordedAt','correlationId']),check(not jsonb_path_exists(snapshot,'$.* ? (@ == null)')),check(snapshot->>'id'=id and (snapshot->>'version')::numeric=version and jsonb_typeof(snapshot->'enabled')='boolean' and (snapshot->>'thresholdMinutes')::numeric between 0 and 525600 and (snapshot->>'frequencyMinutes')::numeric between 1 and 525600 and length(btrim(snapshot->>'reason')) between 1 and 1000));
create table tal_reminder_state(tenant varchar(80) primary key,policy_id varchar(40) not null,version bigint not null check(version>=0),snapshot jsonb not null,foreign key(tenant,policy_id) references tal_reminder_policy,check(snapshot->>'policyId'=policy_id and (snapshot->>'version')::numeric=version and snapshot->>'status' in('READY','PAUSED','FAILED') and (snapshot->>'failures')::numeric between 0 and 3 and snapshot->>'nextDue' is not null));
create table tal_reminder_run(tenant varchar(80) not null,id varchar(40) not null,policy_id varchar(40) not null,snapshot jsonb not null,primary key(tenant,id),foreign key(tenant,policy_id) references tal_reminder_policy,check(snapshot ?& array['id','policyId','actor','scheduled','status','processed','delivered','skipped','reason','recordedAt','correlationId']),check(not jsonb_path_exists(snapshot,'$.* ? (@ == null)')),check(snapshot->>'id'=id and snapshot->>'policyId'=policy_id and snapshot->>'status' in('COMMITTED','FAILED','RETRY_REQUESTED') and (snapshot->>'processed')::numeric between 0 and 20 and (snapshot->>'delivered')::numeric>=0 and (snapshot->>'skipped')::numeric between 0 and 20));
create table tal_reminder_item(tenant varchar(80) not null,id varchar(40) not null,run_id varchar(40) not null,event_id varchar(40) not null,day date not null,snapshot jsonb not null,primary key(tenant,id),unique(tenant,event_id,day),foreign key(tenant,run_id) references tal_reminder_run deferrable initially deferred,foreign key(tenant,event_id) references tal_audit,check(snapshot->>'id'=id and snapshot->>'runId'=run_id and snapshot->>'eventId'=event_id and (snapshot->>'day')::date=day and snapshot->>'status' in('DELIVERED','SKIPPED') and (snapshot->>'recipients')::numeric between 0 and 101));
create table tal_reminder_notice(tenant varchar(80) not null,id varchar(40) not null,event_id varchar(40) not null,employee_id varchar(40) not null,day date not null,run_id varchar(40) not null,snapshot jsonb not null,primary key(tenant,id),unique(tenant,event_id,employee_id,day),foreign key(tenant,event_id) references tal_audit,foreign key(tenant,run_id) references tal_reminder_run deferrable initially deferred,check(snapshot->>'id'=id and snapshot->>'eventId'=event_id and snapshot->>'employeeId'=employee_id and snapshot->>'status'='APPROVAL_REMINDER'));
create index tal_reminder_notice_inbox on tal_reminder_notice(tenant,employee_id,day desc);
create function tal_reminder_policy_guard() returns trigger language plpgsql as $$
begin
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/talent/'||new.tenant,0));
 if new.version<>(select coalesce(max(version),0)+1 from tal_reminder_policy where tenant=new.tenant) then raise exception 'Exact next reminder policy revision required';end if;
 return new;
end $$;
create trigger tal_reminder_policy_integrity before insert on tal_reminder_policy for each row execute function tal_reminder_policy_guard();
create function tal_reminder_state_guard() returns trigger language plpgsql as $$
begin
 if (tg_op='INSERT' and new.version<>0) or (tg_op='UPDATE' and (new.tenant<>old.tenant or new.version<>old.version+1)) then raise exception 'Exact next reminder processing revision required';end if;
 if not exists(select 1 from tal_reminder_policy p where p.tenant=new.tenant and p.id=new.policy_id and p.version=(select max(version) from tal_reminder_policy where tenant=new.tenant)) then raise exception 'Processing requires latest reminder policy';end if;
 return new;
end $$;
create trigger tal_reminder_state_integrity before insert or update on tal_reminder_state for each row execute function tal_reminder_state_guard();
create function tal_reminder_result_guard() returns trigger language plpgsql as $$
declare actual_items integer;actual_delivered integer;actual_skipped integer;e jsonb;i jsonb;
begin
 select count(*),count(*) filter(where snapshot->>'status'='SKIPPED') into actual_items,actual_skipped from tal_reminder_item where tenant=new.tenant and run_id=new.id;
 select count(*) into actual_delivered from tal_reminder_notice where tenant=new.tenant and run_id=new.id;
 if actual_items<>(new.snapshot->>'processed')::integer or actual_skipped<>(new.snapshot->>'skipped')::integer or actual_delivered<>(new.snapshot->>'delivered')::integer then raise exception 'Reminder run control totals mismatch';end if;
 for i in select snapshot from tal_reminder_item where tenant=new.tenant and run_id=new.id loop
  select snapshot into strict e from tal_audit where tenant=new.tenant and id=i->>'eventId';
  if i->>'kind'<>e->>'kind' or i->>'recordId'<>e->>'recordId' or (i->>'recipients')::integer<>(select count(*) from tal_reminder_notice where tenant=new.tenant and run_id=new.id and event_id=i->>'eventId') or ((i->>'status'='DELIVERED') is distinct from ((i->>'recipients')::integer>0)) then raise exception 'Reminder item provenance mismatch';end if;
 end loop;
 if exists(select 1 from tal_reminder_notice n join tal_audit a on a.tenant=n.tenant and a.id=n.event_id where n.tenant=new.tenant and n.run_id=new.id and (n.snapshot->>'kind'<>a.kind or n.snapshot->>'recordId'<>a.record_id or (a.snapshot->>'after')::jsonb->'meta'->>'status'<>'SUBMITTED' or not exists(select 1 from tal_reminder_item i where i.tenant=n.tenant and i.event_id=n.event_id and i.day=n.day and i.run_id=n.run_id))) then raise exception 'Reminder delivery requires exact submitted event and item';end if;
 return null;
end $$;
create constraint trigger tal_reminder_result_integrity after insert on tal_reminder_run deferrable initially deferred for each row execute function tal_reminder_result_guard();
do $$ declare t text;f text;begin
 foreach t in array array['tal_reminder_policy','tal_reminder_run','tal_reminder_item','tal_reminder_notice'] loop
 execute format('create trigger %I before update or delete on %I for each row execute function prevent_audit_change()',t||'_immutable',t);
 execute format('create trigger %I before truncate on %I for each statement execute function prevent_audit_change()',t||'_no_truncate',t);
 end loop;
 create trigger tal_reminder_state_no_delete before delete on tal_reminder_state for each row execute function prevent_audit_change();
 create trigger tal_reminder_state_no_truncate before truncate on tal_reminder_state for each statement execute function prevent_audit_change();
 foreach f in array array['tal_reminder_policy_guard','tal_reminder_state_guard','tal_reminder_result_guard'] loop execute format('alter function %I() set search_path to %I,pg_catalog',f,current_schema());end loop;
end $$;
