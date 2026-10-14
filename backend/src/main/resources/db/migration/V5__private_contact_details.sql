alter table hr_employee add column address varchar(120) not null default '';
alter table hr_employee add column emergency_contact_name varchar(120) not null default '';
alter table hr_employee add column emergency_contact_relationship varchar(120) not null default '';
alter table hr_employee add column emergency_contact_phone varchar(120) not null default '';
alter table hr_request drop constraint hr_request_field_check;
alter table hr_request add constraint hr_request_field_check check(field in ('phone','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone'));
alter table hr_version add constraint hr_version_field_check check(field in ('phone','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone'));
