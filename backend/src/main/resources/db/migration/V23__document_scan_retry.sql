create table employee_document_scan_retry (
 tenant varchar(80) not null,document_id varchar(36) not null,attempt integer not null check(attempt between 1 and 3),actor varchar(120) not null,reason varchar(500) not null check(length(trim(reason))>0),idempotency_key varchar(80) not null,expected_attempt integer not null check(expected_attempt=attempt-1),status varchar(12) not null check(status in ('AVAILABLE','REJECTED','QUARANTINED')),scanner varchar(160) not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,scanned_sha256 varchar(64) not null,
 primary key(tenant,document_id,attempt),unique(tenant,document_id,actor,idempotency_key),foreign key(tenant,document_id) references employee_document(tenant,id)
);
create trigger immutable_document_scan_retry before update or delete on employee_document_scan_retry for each row execute function documents_immutable_record();
create function document_retry_integrity() returns trigger language plpgsql as $$
declare original employee_document%rowtype; previous_status varchar(12); previous_attempt integer;
begin
 select * into original from employee_document where tenant=NEW.tenant and id=NEW.document_id for update;
 if not found or original.status<>'QUARANTINED' or original.sha256<>NEW.scanned_sha256 then raise exception 'Retry must match quarantined original content'; end if;
 select status,attempt into previous_status,previous_attempt from employee_document_scan_retry where tenant=NEW.tenant and document_id=NEW.document_id order by attempt desc limit 1;
 if coalesce(previous_status,original.status)<>'QUARANTINED' or NEW.attempt<>coalesce(previous_attempt,0)+1 then raise exception 'Invalid document retry transition'; end if;
 return NEW;
end $$;
create trigger document_retry_integrity before insert on employee_document_scan_retry for each row execute function document_retry_integrity();
create or replace function employee_document_audit_state() returns trigger language plpgsql as $$ begin
 if not exists(select 1 from employee_document d where d.tenant=NEW.tenant and d.id=NEW.document_id and ((NEW.action='DOWNLOAD_AUTHORISED' and coalesce((select r.status from employee_document_scan_retry r where r.tenant=d.tenant and r.document_id=d.id order by attempt desc limit 1),d.status)='AVAILABLE') or (NEW.action in ('UPLOADED',d.status) and NEW.actor=d.requested_by))) then raise exception 'Document audit must match state'; end if;return NEW;end $$;
