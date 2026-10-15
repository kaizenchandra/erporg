create function documents_immutable_record() returns trigger language plpgsql as $$ begin raise exception 'Document records are append only';end $$;
create table employee_document (
 tenant varchar(80) not null,id varchar(36) not null,employee_id varchar(80) not null,filename varchar(119) not null,media_type varchar(20) not null check(media_type='text/plain'),bytes integer not null check(bytes between 1 and 16384),status varchar(12) not null check(status in ('AVAILABLE','REJECTED','QUARANTINED')),scanner varchar(160) not null,reason varchar(500) not null check(length(trim(reason))>0),recorded_at timestamptz not null,object_key varchar(129) not null check(object_key ~ '^[a-f0-9]{64}/[a-f0-9]{64}$'),sha256 varchar(64) not null check(sha256 ~ '^[a-f0-9]{64}$'),requested_by varchar(120) not null,idempotency_key varchar(80) not null,
 primary key(tenant,id),unique(tenant,requested_by,idempotency_key),check(right(object_key,64)=sha256)
);
create table employee_document_audit (
 id bigint generated always as identity primary key,tenant varchar(80) not null,document_id varchar(36) not null,action varchar(30) not null check(action in ('UPLOADED','AVAILABLE','REJECTED','QUARANTINED','DOWNLOAD_AUTHORISED')),actor varchar(120) not null,recorded_at timestamptz not null,reason varchar(500) not null,correlation_id varchar(80) not null,
 foreign key(tenant,document_id) references employee_document(tenant,id)
);
create trigger immutable_employee_document before update or delete on employee_document for each row execute function documents_immutable_record();
create trigger immutable_employee_document_audit before update or delete on employee_document_audit for each row execute function documents_immutable_record();
create function employee_document_audit_state() returns trigger language plpgsql as $$ begin
 if not exists(select 1 from employee_document d where d.tenant=NEW.tenant and d.id=NEW.document_id and ((NEW.action='DOWNLOAD_AUTHORISED' and d.status='AVAILABLE') or (NEW.action in ('UPLOADED',d.status) and NEW.actor=d.requested_by))) then raise exception 'Document audit must match state'; end if;return NEW;end $$;
create trigger document_audit_state before insert on employee_document_audit for each row execute function employee_document_audit_state();
create unique index document_one_upload_audit on employee_document_audit(tenant,document_id,action) where action<>'DOWNLOAD_AUTHORISED';
create function employee_document_integrity() returns trigger language plpgsql as $$ begin
 if (select count(*) from employee_document_audit a where a.tenant=NEW.tenant and a.document_id=NEW.id and a.action='UPLOADED')<>1 or (select count(*) from employee_document_audit a where a.tenant=NEW.tenant and a.document_id=NEW.id and a.action=NEW.status)<>1 then raise exception 'Document upload and scan audit required'; end if;return null;end $$;
create constraint trigger document_integrity after insert on employee_document deferrable initially deferred for each row execute function employee_document_integrity();
