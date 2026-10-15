alter table hr_employee add column position_code varchar(30) not null default '';
alter table hr_request drop constraint hr_request_field_check;
alter table hr_request add constraint hr_request_field_check check(field in ('phone','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone','positionCode'));
alter table hr_version drop constraint hr_version_field_check;
alter table hr_version add constraint hr_version_field_check check(field in ('phone','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone','positionCode'));
alter table hr_request add constraint hr_request_tenant_employee_unique unique(tenant,id,employee_id);
create table hr_position_assignment (
 tenant varchar(80) not null,employee_id varchar(40) not null,position_code varchar(30),effective_date date not null,recorded_at timestamptz not null,request_id varchar(40) not null primary key,
 foreign key(tenant,employee_id) references hr_employee(tenant,id),foreign key(tenant,position_code) references hr_position(tenant,code),foreign key(tenant,request_id,employee_id) references hr_request(tenant,id,employee_id)
);
create index hr_position_assignment_effective on hr_position_assignment(tenant,employee_id,effective_date desc,recorded_at desc);
create trigger immutable_position_assignment before update or delete on hr_position_assignment for each row execute function prevent_audit_change();
