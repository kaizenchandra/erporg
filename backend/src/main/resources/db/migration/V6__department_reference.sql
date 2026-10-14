create table hr_department (
 tenant varchar(80) not null, code varchar(30) not null check(code ~ '^[A-Z0-9-]{2,30}$'), name varchar(120) not null check(length(trim(name))>0),
 parent_code varchar(30), cost_centre varchar(30) not null check(cost_centre ~ '^[A-Z0-9-]{2,30}$'), effective_from date not null,
 created_by varchar(120) not null, idempotency_key varchar(80) not null,
 primary key(tenant,code), unique(tenant,name), unique(tenant,created_by,idempotency_key),
 foreign key(tenant,parent_code) references hr_department(tenant,code), check(parent_code is null or parent_code<>code)
);
-- Preserve existing department names. DEMO cost centres require finance-owner configuration before release.
insert into hr_department(tenant,code,name,cost_centre,effective_from,created_by,idempotency_key)
 select distinct tenant,'D-'||upper(substr(md5(department),1,10)),department,'DEMO-'||upper(substr(md5(department),1,8)),date '1970-01-01','migration','seed-'||upper(substr(md5(department),1,10)) from hr_employee;
create table hr_department_audit(
 id bigint generated always as identity primary key, tenant varchar(80) not null, code varchar(30) not null, actor varchar(120) not null,
 reason varchar(500) not null, recorded_at timestamptz not null,effective_from date not null,correlation_id varchar(80) not null,
 foreign key(tenant,code) references hr_department(tenant,code)
);
create trigger immutable_department_audit before update or delete on hr_department_audit for each row execute function prevent_audit_change();
