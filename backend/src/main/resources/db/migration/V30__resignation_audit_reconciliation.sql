-- Preserve V29; strengthen reconciliation for future accidental audit-only writes.
create or replace function check_resignation_reconciliation() returns trigger language plpgsql as $$
declare r hr_resignation;
begin
 if TG_TABLE_NAME='hr_resignation' then
 select * into r from hr_resignation where tenant=new.tenant and id=new.id;
 else
 select * into r from hr_resignation where tenant=new.tenant and id=new.request_id;
 end if;
 if (select count(*) from hr_resignation_audit a where a.tenant=r.tenant and a.request_id=r.id)<>(case when r.status='SUBMITTED' then 1 else 2 end)
 or not exists(select 1 from hr_resignation_audit a where a.tenant=r.tenant and a.request_id=r.id and a.old_status is null and a.new_status='SUBMITTED' and a.actor=r.requested_by and a.reason=r.reason and a.effective_date=r.last_working_date and a.recorded_at=r.recorded_at)
 or (r.status<>'SUBMITTED' and not exists(select 1 from hr_resignation_audit a where a.tenant=r.tenant and a.request_id=r.id and a.old_status='SUBMITTED' and a.new_status=r.status and a.actor=r.decided_by and a.reason=r.comment and a.effective_date=r.last_working_date and a.recorded_at=r.decided_at))
 or (r.status='APPROVED') is distinct from exists(select 1 from hr_resignation_handoff h join hr_resignation_audit a on a.tenant=h.tenant and a.request_id=h.request_id and a.new_status='APPROVED' and a.correlation_id=h.correlation_id and a.recorded_at=h.recorded_at where h.tenant=r.tenant and h.request_id=r.id and h.employee_id=r.employee_id and h.effective_date=r.last_working_date)
 then raise exception 'Resignation audit/handoff mismatch'; end if;
 return null;
end $$;
create constraint trigger resignation_audit_reconciled after insert on hr_resignation_audit deferrable initially deferred for each row execute function check_resignation_reconciliation();
