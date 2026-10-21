-- Delegation only assigns responsibility to HR users who already possess review authority.
-- It does not grant directory, salary, employment or approval permissions.
create table tal_delegation(tenant varchar(80) not null,id varchar(40) not null,kind varchar(30) not null check(kind in('requisitions','compensation','reviews')),record_id varchar(40) not null,record_version bigint not null check(record_version>=0),actor varchar(120) not null,command_key varchar(80) not null,snapshot jsonb not null,primary key(tenant,id),unique(tenant,actor,command_key),check(snapshot ?& array['id','kind','recordId','recordVersion','originalEmployee','delegateEmployee','from','through','actor','reason','commandKey','recordedAt','correlationId']),check(snapshot->>'id'=id and snapshot->>'kind'=kind and snapshot->>'recordId'=record_id and (snapshot->>'recordVersion')::bigint=record_version and snapshot->>'actor'=actor and snapshot->>'commandKey'=command_key and snapshot->>'originalEmployee'<>snapshot->>'delegateEmployee' and length(btrim(snapshot->>'reason')) between 1 and 1000 and (snapshot->>'through')::date between (snapshot->>'from')::date and ((snapshot->>'from')::date+interval '1 year')::date));
create index tal_delegation_record on tal_delegation(tenant,kind,record_id,record_version);
create table tal_delegation_revocation(tenant varchar(80) not null,delegation_id varchar(40) not null,snapshot jsonb not null,primary key(tenant,delegation_id),foreign key(tenant,delegation_id) references tal_delegation,check(snapshot ?& array['delegationId','actor','actorEmployee','reason','commandKey','recordedAt','correlationId']),check(snapshot->>'delegationId'=delegation_id and length(btrim(snapshot->>'reason')) between 1 and 1000));
create function tal_delegation_guard() returns trigger language plpgsql as $$
declare r jsonb;owned text;
begin
 perform pg_advisory_xact_lock(hashtextextended(current_schema()||'/talent/'||new.tenant,0));
 owned:=case new.kind when 'reviews' then 'tal_review' when 'compensation' then 'tal_compensation' when 'requisitions' then 'tal_requisition' end;
 execute format('select snapshot from %I where tenant=$1 and id=$2',owned) into strict r using new.tenant,new.record_id;
 if r->'meta'->>'status'<>'SUBMITTED' or (r->'meta'->>'version')::bigint<>new.record_version or (new.kind<>'reviews' and (r->'meta'->>'preparedEmployee' in(new.snapshot->>'originalEmployee',new.snapshot->>'delegateEmployee'))) or (new.kind='reviews' and r->>'employeeId' in(new.snapshot->>'originalEmployee',new.snapshot->>'delegateEmployee')) then raise exception 'Current independent review required';end if;
 if exists(select 1 from tal_delegation d where d.tenant=new.tenant and d.kind=new.kind and d.record_id=new.record_id and d.record_version=new.record_version and (d.snapshot->>'from')::date<=(new.snapshot->>'through')::date and (d.snapshot->>'through')::date>=(new.snapshot->>'from')::date and not exists(select 1 from tal_delegation_revocation v where v.tenant=d.tenant and v.delegation_id=d.id)) then raise exception 'Overlapping approval responsibility';end if;
 return new;
end $$;
create trigger tal_delegation_integrity before insert on tal_delegation for each row execute function tal_delegation_guard();
do $$ declare t text;begin
 foreach t in array array['tal_delegation','tal_delegation_revocation'] loop
 execute format('create trigger %I before update or delete on %I for each row execute function prevent_audit_change()',t||'_immutable',t);
 execute format('create trigger %I before truncate on %I for each statement execute function prevent_audit_change()',t||'_no_truncate',t);
 end loop;
 execute format('alter function tal_delegation_guard() set search_path to %I,pg_catalog',current_schema());
end $$;
