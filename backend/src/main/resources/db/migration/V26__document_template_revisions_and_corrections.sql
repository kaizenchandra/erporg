create table document_template_revision (
 tenant varchar(80) not null,code varchar(32) not null,version integer not null check(version between 2 and 100),title varchar(100) not null check(length(trim(title))>0),body varchar(4000) not null check(length(trim(body))>0),required boolean not null,effective_date date not null,retired boolean not null,actor varchar(120) not null,reason varchar(500) not null check(length(trim(reason))>0),recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,code,version),unique(tenant,actor,idempotency_key),foreign key(tenant,code) references document_template(tenant,code)
);
create trigger immutable_document_template_revision before update or delete on document_template_revision for each row execute function documents_immutable_record();
create function document_template_revision_integrity() returns trigger language plpgsql as $$
declare original document_template%rowtype; previous document_template_revision%rowtype;
begin
 select * into original from document_template where tenant=NEW.tenant and code=NEW.code for update;
 select * into previous from document_template_revision where tenant=NEW.tenant and code=NEW.code order by version desc limit 1;
 if original.code is null or NEW.version<>coalesce(previous.version,1)+1 or coalesce(previous.retired,false) or NEW.effective_date<coalesce(previous.effective_date,original.effective_date) then raise exception 'Invalid template revision sequence/date/state'; end if;
 return NEW;
end $$;
create trigger document_template_revision_integrity before insert on document_template_revision for each row execute function document_template_revision_integrity();
create table document_template_reclassification (
 tenant varchar(80) not null,code varchar(32) not null,employee_id varchar(40) not null,classification_version integer not null check(classification_version between 2 and 100),previous_root_id varchar(36) not null,root_id varchar(36) not null,linked_version integer not null check(linked_version between 1 and 100),actor varchar(120) not null,reason varchar(500) not null check(length(trim(reason))>0),recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,code,employee_id,classification_version),unique(tenant,root_id),unique(tenant,actor,idempotency_key),foreign key(tenant,code,employee_id) references document_template_evidence(tenant,code,employee_id),foreign key(tenant,root_id) references employee_document(tenant,id),foreign key(tenant,previous_root_id) references employee_document(tenant,id),check(root_id<>previous_root_id)
);
create trigger immutable_document_template_reclassification before update or delete on document_template_reclassification for each row execute function documents_immutable_record();
create function document_reclassification_integrity() returns trigger language plpgsql as $$
declare original document_template_evidence%rowtype; previous document_template_reclassification%rowtype; root employee_document%rowtype; latest_id varchar(36); ordinal integer; scan_status varchar(12); initial_status varchar(12);
begin
 select * into original from document_template_evidence where tenant=NEW.tenant and code=NEW.code and employee_id=NEW.employee_id for update;
 select * into previous from document_template_reclassification where tenant=NEW.tenant and code=NEW.code and employee_id=NEW.employee_id order by classification_version desc limit 1;
 if original.root_id is null or NEW.classification_version<>coalesce(previous.classification_version,1)+1 or NEW.previous_root_id<>coalesce(previous.root_id,original.root_id) then raise exception 'Invalid classification sequence/previous chain'; end if;
 select * into root from employee_document where tenant=NEW.tenant and id=NEW.root_id for update;
 if root.id is null or root.employee_id<>NEW.employee_id or exists(select 1 from employee_document_version where tenant=NEW.tenant and document_id=NEW.root_id) or exists(select 1 from document_template_evidence where tenant=NEW.tenant and root_id=NEW.root_id) or exists(select 1 from document_template_reclassification where tenant=NEW.tenant and root_id=NEW.root_id) then raise exception 'Invalid or reserved classification chain'; end if;
 select document_id,version into latest_id,ordinal from employee_document_version where tenant=NEW.tenant and root_id=NEW.root_id order by version desc limit 1;
 if NEW.linked_version<>coalesce(ordinal,1) then raise exception 'Stale corrected evidence version'; end if;
 select status into initial_status from employee_document where tenant=NEW.tenant and id=coalesce(latest_id,NEW.root_id);
 select status into scan_status from employee_document_scan_retry where tenant=NEW.tenant and document_id=coalesce(latest_id,NEW.root_id) order by attempt desc limit 1;
 if coalesce(scan_status,initial_status)<>'AVAILABLE' then raise exception 'Corrected evidence must be clean'; end if;
 return NEW;
end $$;
create trigger document_reclassification_integrity before insert on document_template_reclassification for each row execute function document_reclassification_integrity();
create function document_initial_classification_reservation() returns trigger language plpgsql as $$
begin
 perform 1 from employee_document where tenant=NEW.tenant and id=NEW.root_id for update;
 if exists(select 1 from document_template_reclassification where tenant=NEW.tenant and root_id=NEW.root_id) then raise exception 'Chain reserved in correction history'; end if;
 return NEW;
end $$;
create trigger document_initial_classification_reservation before insert on document_template_evidence for each row execute function document_initial_classification_reservation();
