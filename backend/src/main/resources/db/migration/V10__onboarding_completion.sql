create table hr_onboarding_completion(
 tenant varchar(80) not null,hire_id varchar(40) not null references hr_hire_request(id),employee_id varchar(40) not null,
 actor varchar(120) not null,reason varchar(500) not null check(length(trim(reason))>0),input_version bigint not null,new_version bigint not null,
 old_status varchar(20) not null check(old_status='Onboarding'),new_status varchar(20) not null check(new_status='Active'),
 effective_date date not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,hire_id),unique(tenant,employee_id),unique(tenant,actor,idempotency_key),foreign key(tenant,employee_id) references hr_employee(tenant,id),check(new_version=input_version+1)
);
create trigger immutable_onboarding_completion before update or delete on hr_onboarding_completion for each row execute function prevent_audit_change();
