alter table hr_request drop constraint hr_request_field_check;
alter table hr_request add constraint hr_request_field_check check(field in ('phone','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone','positionCode','managerId'));
alter table hr_version drop constraint hr_version_field_check;
alter table hr_version add constraint hr_version_field_check check(field in ('phone','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone','positionCode','managerId'));
create table hr_reporting_assignment (
 tenant varchar(80) not null,employee_id varchar(40) not null,manager_id varchar(40) not null,effective_date date not null,recorded_at timestamptz not null,request_id varchar(40) primary key,
 check(employee_id<>manager_id),foreign key(tenant,employee_id) references hr_employee(tenant,id),foreign key(tenant,manager_id) references hr_employee(tenant,id),foreign key(tenant,request_id,employee_id) references hr_request(tenant,id,employee_id)
);
create trigger immutable_reporting_assignment before update or delete on hr_reporting_assignment for each row execute function prevent_audit_change();
create function validate_reporting_assignment() returns trigger language plpgsql as $$ begin
 if not exists(select 1 from hr_request r join hr_version v on v.tenant=r.tenant and v.employee_id=r.employee_id and v.request_id=r.id where r.tenant=new.tenant and r.id=new.request_id and r.employee_id=new.employee_id and r.status='APPROVED' and r.field='managerId' and r.new_value=new.manager_id and r.effective_date=new.effective_date and v.field='managerId' and v.value=new.manager_id and v.effective_date=new.effective_date and v.recorded_at=new.recorded_at) then raise exception 'Reporting assignment must match approved employment version'; end if; return new; end $$;
create trigger consistent_reporting_assignment before insert on hr_reporting_assignment for each row execute function validate_reporting_assignment();
