-- Entity source provenance cannot be rewritten after the requester snapshot is captured.
create function protect_entity_request_provenance() returns trigger language plpgsql as $$
begin
 if old.field='legalEntityCode' or (tg_op='UPDATE' and new.field='legalEntityCode') then
  if tg_op='DELETE' then raise exception 'Entity request provenance is immutable';end if;
  if row(old.id,old.tenant,old.employee_id,old.field,old.old_value,old.new_value,old.effective_date,old.reason,old.requested_by,old.employee_version,old.recorded_at,old.idempotency_key)
     is distinct from row(new.id,new.tenant,new.employee_id,new.field,new.old_value,new.new_value,new.effective_date,new.reason,new.requested_by,new.employee_version,new.recorded_at,new.idempotency_key)
     or (old.status<>'SUBMITTED' and row(old.status,old.decided_by,old.comment) is distinct from row(new.status,new.decided_by,new.comment)) then
   raise exception 'Entity request provenance is immutable';
  end if;
 end if;
 return case when tg_op='DELETE' then old else new end;
end $$;
create trigger protect_entity_request_provenance before update or delete on hr_request for each row execute function protect_entity_request_provenance();
