create function validate_position_assignment() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from hr_request r join hr_version v on v.tenant=r.tenant and v.request_id=r.id and v.employee_id=r.employee_id
 where r.tenant=new.tenant and r.id=new.request_id and r.employee_id=new.employee_id and r.status='APPROVED' and r.field='positionCode'
 and r.new_value=coalesce(new.position_code,'') and r.effective_date=new.effective_date
 and v.field='positionCode' and v.value=r.new_value and v.effective_date=new.effective_date and v.recorded_at=new.recorded_at)
 then raise exception 'Position assignment must match approved employment version'; end if;
 return new;
end $$;
create trigger consistent_position_assignment before insert on hr_position_assignment for each row execute function validate_position_assignment();
