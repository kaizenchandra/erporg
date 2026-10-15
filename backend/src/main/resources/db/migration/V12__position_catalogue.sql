create table hr_position (
 tenant varchar(80) not null,code varchar(30) not null,title varchar(120) not null check(length(trim(title))>0),department_code varchar(30) not null,effective_from date not null,
 created_by varchar(120) not null,idempotency_key varchar(80) not null,
 primary key(tenant,code),unique(tenant,created_by,idempotency_key),foreign key(tenant,department_code) references hr_department(tenant,code),
 check(code ~ '^[A-Z0-9-]{2,30}$')
);
create index hr_position_department on hr_position(tenant,department_code);
create table hr_position_audit (
 id bigint generated always as identity primary key,tenant varchar(80) not null,code varchar(30) not null,actor varchar(120) not null,reason varchar(500) not null check(length(trim(reason))>0),
 recorded_at timestamptz not null,effective_from date not null,correlation_id varchar(80) not null,
 unique(tenant,code),foreign key(tenant,code) references hr_position(tenant,code)
);
create trigger immutable_position before update or delete on hr_position for each row execute function prevent_audit_change();
create trigger immutable_position_audit before update or delete on hr_position_audit for each row execute function prevent_audit_change();
