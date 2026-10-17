-- Correlation IDs and timestamps describe an event; neither is its identity.
-- Preserve old handoffs but suppress them whenever a replacement was approved.
create or replace view hr_employment_end_handoff_current as
 select h.tenant,h.employee_id,h.target,h.inactive_date,h.recorded_at,h.correlation_id,
 case when exists(select 1 from hr_employment_end_current e where e.tenant=h.tenant and e.employee_id=h.employee_id)
 and not exists(select 1 from hr_end_reschedule_current r where r.tenant=h.tenant and r.employee_id=h.employee_id and r.status='APPROVED') then h.status else 'SUPPRESSED' end::varchar as status
 from hr_employment_end_handoff h
 union all
 select h.tenant,r.employee_id,h.target,h.inactive_date,h.recorded_at,h.correlation_id,h.status from hr_end_reschedule_handoff h join hr_end_reschedule r on r.tenant=h.tenant and r.id=h.reschedule_id;
