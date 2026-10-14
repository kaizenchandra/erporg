-- Record bootstrap reference changes without modifying prior migration history.
insert into hr_department_audit(tenant,code,actor,reason,recorded_at,effective_from,correlation_id)
 select d.tenant,d.code,'migration','Existing employee department preserved; DEMO cost centre requires configuration.',now(),d.effective_from,'migration-v7'
 from hr_department d where d.created_by='migration' and not exists(select 1 from hr_department_audit a where a.tenant=d.tenant and a.code=d.code);
