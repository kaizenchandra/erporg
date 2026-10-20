-- Synthetic role fixtures only, excluded when the local profile is disabled.
-- No wildcard entity, no automatic grant based on an HR/manager role, no grant for employees.
insert into fin_grant(tenant,subject,employee_id,entity,permission) values
('atlas','amelia','E001','DEMO-FIN','READ'),('atlas','amelia','E001','DEMO-FIN','SETUP'),('atlas','amelia','E001','DEMO-FIN','PREPARE'),('atlas','amelia','E001','DEMO-FIN','PAYROLL_PREPARE'),
('atlas','olivia','E005','DEMO-FIN','READ'),('atlas','olivia','E005','DEMO-FIN','APPROVE'),('atlas','olivia','E005','DEMO-FIN','PAYROLL_APPROVE'),
('atlas','marcus','E002','DEMO-FIN','READ'),('atlas','marcus','E002','DEMO-FIN','POST');
