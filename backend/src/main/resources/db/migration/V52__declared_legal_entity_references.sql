-- Declared configuration references only. No employee backfill or statutory activation.
create table hr_legal_entity(
 tenant varchar(40) not null,
 code varchar(30) not null check(code ~ '^[A-Z0-9-]{2,30}$'),
 name varchar(120) not null check(length(trim(name)) between 1 and 120 and name=trim(name) and name !~ '[[:cntrl:]]'),
 country_code varchar(2) not null check(country_code ~ '^[A-Z]{2}$'),
 currency_code varchar(3) not null check(currency_code ~ '^[A-Z]{3}$'),
 time_zone varchar(80) not null check(time_zone ~ '^[A-Za-z0-9_+/-]{1,80}$'),
 effective_from date not null,
 created_by varchar(120) not null check(length(trim(created_by))>0),
 reason varchar(500) not null check(length(trim(reason)) between 1 and 500 and reason=trim(reason) and reason !~ '[[:cntrl:]]'),
 idempotency_key varchar(80) not null check(idempotency_key ~ '^[a-zA-Z0-9-]{8,80}$'),
 recorded_at timestamptz not null,
 correlation_id varchar(100) not null check(length(trim(correlation_id))>0),
 primary key(tenant,code),unique(tenant,created_by,idempotency_key)
);
create trigger immutable_legal_entity before update or delete on hr_legal_entity for each row execute function prevent_audit_change();
create trigger immutable_legal_entity_truncate before truncate on hr_legal_entity for each statement execute function prevent_audit_change();
