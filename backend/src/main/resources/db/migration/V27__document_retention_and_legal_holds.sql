create table document_retention_event (
 tenant varchar(80) not null,root_id varchar(36) not null,employee_id varchar(40) not null,version integer not null check(version between 1 and 100),action varchar(20) not null check(action in ('SET_POLICY','PLACE_HOLD','RELEASE_HOLD')),retain_until date,basis varchar(1000),hold_id varchar(36),case_reference varchar(80),actor varchar(120) not null,actor_employee_id varchar(40) not null,reason varchar(500) not null check(length(trim(reason))>0),recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,root_id,version),unique(tenant,actor,idempotency_key),foreign key(tenant,root_id) references employee_document(tenant,id),
 check((action='SET_POLICY' and retain_until is not null and basis is not null and length(trim(basis))>0 and hold_id is null and case_reference is null) or (action='PLACE_HOLD' and retain_until is null and basis is null and hold_id is not null and case_reference is not null and length(trim(case_reference))>0) or (action='RELEASE_HOLD' and retain_until is null and basis is null and hold_id is not null and case_reference is null))
);
create unique index document_hold_placement_unique on document_retention_event(tenant,root_id,hold_id) where action='PLACE_HOLD';
create unique index document_hold_release_unique on document_retention_event(tenant,root_id,hold_id) where action='RELEASE_HOLD';
create trigger immutable_document_retention before update or delete on document_retention_event for each row execute function documents_immutable_record();
create function document_retention_integrity() returns trigger language plpgsql as $$
declare original employee_document%rowtype; ordinal integer; previous_date date; placement document_retention_event%rowtype;
begin
 select * into original from employee_document where tenant=NEW.tenant and id=NEW.root_id for update;
 if original.id is null or original.employee_id<>NEW.employee_id or exists(select 1 from employee_document_version where tenant=NEW.tenant and document_id=NEW.root_id) then raise exception 'Invalid governance owner/root'; end if;
 select max(version) into ordinal from document_retention_event where tenant=NEW.tenant and root_id=NEW.root_id;
 if NEW.version<>coalesce(ordinal,0)+1 then raise exception 'Governance events must be sequential'; end if;
 if NEW.action='SET_POLICY' then
  select retain_until into previous_date from document_retention_event where tenant=NEW.tenant and root_id=NEW.root_id and action='SET_POLICY' order by version desc limit 1;
  if previous_date is not null and NEW.retain_until<previous_date then raise exception 'Retention cannot be shortened'; end if;
 end if;
 if NEW.action='PLACE_HOLD' and exists(select 1 from document_retention_event p where p.tenant=NEW.tenant and p.root_id=NEW.root_id and p.action='PLACE_HOLD' and p.case_reference=NEW.case_reference and not exists(select 1 from document_retention_event r where r.tenant=p.tenant and r.root_id=p.root_id and r.action='RELEASE_HOLD' and r.hold_id=p.hold_id)) then raise exception 'Case already has active hold'; end if;
 if NEW.action='RELEASE_HOLD' then
  select * into placement from document_retention_event where tenant=NEW.tenant and root_id=NEW.root_id and action='PLACE_HOLD' and hold_id=NEW.hold_id;
  if placement.hold_id is null or placement.actor_employee_id=NEW.actor_employee_id or exists(select 1 from document_retention_event where tenant=NEW.tenant and root_id=NEW.root_id and action='RELEASE_HOLD' and hold_id=NEW.hold_id) then raise exception 'Independent HR release of active hold required'; end if;
 end if;
 return NEW;
end $$;
create trigger document_retention_integrity before insert on document_retention_event for each row execute function document_retention_integrity();
