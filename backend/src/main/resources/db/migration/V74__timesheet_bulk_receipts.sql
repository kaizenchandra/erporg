-- Whole-selection fingerprint plus individual, typed decision receipts, committed atomically.
create table time_batch_command (
 tenant varchar(80) not null, subject varchar(120) not null, command_key varchar(47) not null,
 fingerprint char(64) not null, primary key(tenant,subject,command_key)
);
create trigger time_batch_command_immutable before update or delete on time_batch_command for each row execute function prevent_audit_change();
create trigger time_batch_command_no_truncate before truncate on time_batch_command for each statement execute function prevent_audit_change();
