create table document_template (
 tenant varchar(80) not null,code varchar(32) not null check(code ~ '^[A-Z][A-Z0-9_-]{1,31}$'),title varchar(100) not null check(length(trim(title))>0),body varchar(4000) not null check(length(trim(body))>0),required boolean not null,effective_date date not null,actor varchar(120) not null,reason varchar(500) not null check(length(trim(reason))>0),recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,code),unique(tenant,actor,idempotency_key)
);
create trigger immutable_document_template before update or delete on document_template for each row execute function documents_immutable_record();
create table document_template_evidence (
 tenant varchar(80) not null,code varchar(32) not null,employee_id varchar(40) not null,root_id varchar(36) not null,linked_version integer not null check(linked_version between 1 and 100),actor varchar(120) not null,reason varchar(500) not null check(length(trim(reason))>0),recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,code,employee_id),unique(tenant,root_id),unique(tenant,actor,idempotency_key),foreign key(tenant,code) references document_template(tenant,code),foreign key(tenant,root_id) references employee_document(tenant,id)
);
create trigger immutable_document_template_evidence before update or delete on document_template_evidence for each row execute function documents_immutable_record();
create function document_template_evidence_integrity() returns trigger language plpgsql as $$
declare root employee_document%rowtype; latest employee_document%rowtype; ordinal integer; latest_id varchar(36); scan_status varchar(20);
begin
 select * into root from employee_document where tenant=NEW.tenant and id=NEW.root_id for update;
 if root.id is null or root.employee_id<>NEW.employee_id or exists(select 1 from employee_document_version where tenant=NEW.tenant and document_id=NEW.root_id) then raise exception 'Invalid document evidence owner/root'; end if;
 select document_id,version into latest_id,ordinal from employee_document_version where tenant=NEW.tenant and root_id=NEW.root_id order by version desc limit 1;
 if NEW.linked_version<>coalesce(ordinal,1) then raise exception 'Stale document evidence version'; end if;
 select * into latest from employee_document where tenant=NEW.tenant and id=coalesce(latest_id,NEW.root_id);
 select status into scan_status from employee_document_scan_retry where tenant=NEW.tenant and document_id=latest.id order by attempt desc limit 1;
 if coalesce(scan_status,latest.status)<>'AVAILABLE' then raise exception 'Document evidence requires clean latest version'; end if;
 return NEW;
end $$;
create trigger document_template_evidence_integrity before insert on document_template_evidence for each row execute function document_template_evidence_integrity();
