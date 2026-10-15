alter table hr_hire_request add constraint hr_hire_request_tenant_id_unique unique(tenant,id);
alter table hr_hire_request add constraint hr_hire_request_employee_link_unique unique(tenant,id,employee_id);
alter table hr_hire_audit add constraint hr_hire_audit_tenant_fk foreign key(tenant,request_id) references hr_hire_request(tenant,id);
alter table hr_onboarding_completion add constraint hr_onboarding_hire_employee_fk foreign key(tenant,hire_id,employee_id) references hr_hire_request(tenant,id,employee_id);
