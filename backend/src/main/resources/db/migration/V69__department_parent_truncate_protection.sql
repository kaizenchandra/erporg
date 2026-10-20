-- Row-level UPDATE/DELETE guards cannot intercept TRUNCATE.
create trigger immutable_department_parent_truncate before truncate on hr_department_parent_change for each statement execute function prevent_audit_change();
create trigger immutable_department_declaration_truncate before truncate on hr_department for each statement execute function guard_department_declaration();
