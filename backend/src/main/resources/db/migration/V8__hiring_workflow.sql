create unique index hr_employee_email_normalized on hr_employee(tenant,lower(email));
create table hr_hire_request (
 id varchar(40) primary key,tenant varchar(80) not null,name varchar(120) not null,email varchar(160) not null,phone varchar(30) not null,
 title varchar(120) not null,department varchar(120) not null,location varchar(120) not null,manager_id varchar(40) not null,start_date date not null,
 reason varchar(500) not null,status varchar(20) not null check(status in ('SUBMITTED','APPROVED','REJECTED')),
 requested_by varchar(120) not null,requester_employee_id varchar(40) not null,employee_id varchar(40),recorded_at timestamptz not null,idempotency_key varchar(80) not null,decided_by varchar(120),comment varchar(500),
 unique(tenant,requested_by,idempotency_key),foreign key(tenant,manager_id) references hr_employee(tenant,id),foreign key(tenant,requester_employee_id) references hr_employee(tenant,id),foreign key(tenant,employee_id) references hr_employee(tenant,id),
 check((status='SUBMITTED' and employee_id is null and decided_by is null) or (status='APPROVED' and employee_id is not null and decided_by is not null and decided_by<>requested_by) or (status='REJECTED' and employee_id is null and decided_by is not null and decided_by<>requested_by))
);
create table hr_hire_audit(id bigint generated always as identity primary key,tenant varchar(80) not null,request_id varchar(40) not null references hr_hire_request(id),old_status varchar(20),new_status varchar(20) not null,actor varchar(120) not null,reason varchar(500) not null,recorded_at timestamptz not null,effective_date date not null,correlation_id varchar(80) not null);
create trigger immutable_hire_audit before update or delete on hr_hire_audit for each row execute function prevent_audit_change();
create table hr_employee_event(id bigint generated always as identity primary key,tenant varchar(80) not null,event_type varchar(40) not null,employee_id varchar(40) not null,effective_date date not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,delivery_status varchar(20) not null check(delivery_status in ('PENDING','DELIVERED','FAILED')),foreign key(tenant,employee_id) references hr_employee(tenant,id),unique(tenant,event_type,employee_id));
