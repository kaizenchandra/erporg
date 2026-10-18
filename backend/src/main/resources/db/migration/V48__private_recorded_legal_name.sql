-- No inferred employee backfill. An unrecorded legal name reads as empty until an approved version exists.
alter table hr_request drop constraint hr_request_field_check;
alter table hr_request add constraint hr_request_field_check check(field in('phone','name','legalName','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone','positionCode','managerId'));
alter table hr_version drop constraint hr_version_field_check;
alter table hr_version add constraint hr_version_field_check check(field in('phone','name','legalName','title','department','location','address','emergencyContactName','emergencyContactRelationship','emergencyContactPhone','positionCode','managerId'));
alter table hr_request add constraint legal_name_request_shape check(field<>'legalName' or(length(trim(new_value)) between 1 and 120 and new_value !~ '[[:cntrl:]]'));
alter table hr_version add constraint legal_name_version_shape check(field<>'legalName' or(length(trim(value)) between 1 and 120 and value !~ '[[:cntrl:]]'));
