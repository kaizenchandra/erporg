-- Add checks without changing V87, already exercised by isolated acceptance schemas.
alter table tal_delegation add constraint tal_delegation_exact_revision check((snapshot->>'recordVersion')::numeric=record_version);
alter table tal_delegation add constraint tal_delegation_nonnull_fields check(not jsonb_path_exists(snapshot,'$.* ? (@ == null)'));
alter table tal_delegation_revocation add constraint tal_delegation_revocation_nonnull_fields check(not jsonb_path_exists(snapshot,'$.* ? (@ == null)'));
