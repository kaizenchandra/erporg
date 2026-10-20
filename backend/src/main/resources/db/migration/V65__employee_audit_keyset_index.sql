-- Selection support only; no audit rewrite or changed retention.
set local lock_timeout = '5s';
create index hr_audit_employee_recorded_id on hr_audit(tenant,employee_id,recorded_at desc,id desc);
