create table hr_employee (
 tenant varchar(80) not null, id varchar(40) not null, name varchar(120) not null,
 email varchar(160) not null, phone varchar(30) not null, title varchar(120) not null,
 department varchar(120) not null, location varchar(120) not null,
 status varchar(20) not null check(status in ('Active','On leave','Onboarding')),
 manager_id varchar(40), start_date date not null, version bigint not null default 0 check(version>=0),
 primary key(tenant,id), unique(tenant,email), foreign key(tenant,manager_id) references hr_employee(tenant,id) deferrable initially deferred
);
create table identity_membership(subject varchar(120) primary key,tenant varchar(80) not null,employee_id varchar(40) not null,name varchar(120) not null,role varchar(20) not null check(role in ('HR','MANAGER','EMPLOYEE')),foreign key(tenant,employee_id) references hr_employee(tenant,id));
create table hr_request (
 id varchar(40) primary key, tenant varchar(80) not null, employee_id varchar(40) not null,
 field varchar(30) not null check(field in ('phone','title','department','location')),
 old_value varchar(120) not null, new_value varchar(120) not null, effective_date date not null,
 reason varchar(500) not null, status varchar(20) not null check(status in ('SUBMITTED','APPROVED','REJECTED')),
 requested_by varchar(120) not null, employee_version bigint not null, recorded_at timestamptz not null,
 idempotency_key varchar(80) not null, decided_by varchar(120), comment varchar(500),
 foreign key(tenant,employee_id) references hr_employee(tenant,id), unique(tenant,requested_by,idempotency_key),
 check((status='SUBMITTED' and decided_by is null) or (status<>'SUBMITTED' and decided_by is not null and decided_by<>requested_by))
);
create table hr_version (id bigint generated always as identity primary key,tenant varchar(80) not null,employee_id varchar(40) not null,field varchar(30) not null,value varchar(120) not null,effective_date date not null,recorded_at timestamptz not null,request_id varchar(40) not null unique references hr_request(id),foreign key(tenant,employee_id) references hr_employee(tenant,id));
create index hr_version_effective on hr_version(tenant,employee_id,field,effective_date desc);
create table hr_audit(id bigint generated always as identity primary key,tenant varchar(80) not null,employee_id varchar(40) not null,request_id varchar(40) not null references hr_request(id),actor varchar(120) not null,action varchar(20) not null,field varchar(30) not null,old_value varchar(120) not null,new_value varchar(120) not null,reason varchar(500) not null,recorded_at timestamptz not null,correlation_id varchar(80) not null);
create table hr_notification(id bigint generated always as identity primary key,tenant varchar(80) not null,subject varchar(120) not null,message varchar(500) not null,recorded_at timestamptz not null);
create function prevent_audit_change() returns trigger language plpgsql as $$ begin raise exception 'Audit events are append only'; end $$;
create trigger immutable_audit before update or delete on hr_audit for each row execute function prevent_audit_change();
