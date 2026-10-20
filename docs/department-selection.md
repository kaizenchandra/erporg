# Phase 2 increment BB: complete department selection in Core HR workflows

Prerequisites: BA's complete tenant department directory and existing hire/rehire/transfer/position command policies. Scope: HR-02/04/05, SH-01/06/09; existing approvals/audit remain authoritative. No source ID or Phase2 is complete through this increment.

Replace legacy capped arrays in employee-change department selection, hiring, rehire, position creation and position filtering with one bounded searchable picker. Preserve name-valued employment contracts versus code-valued position contracts explicitly. Keep selected values during query changes, pagination and read failure; search does not modify the command. Expose loading/empty/error/retry, page totals, persistent labels and linked server validation without making the browser a policy authority. Backend revalidates department/date at submission and approval. Planned references are shown with start dates; selection does not grant eligibility.

Positions retain their resolved department names using an application-owned listing projection from the Core HR adapter; do not fetch the full catalogue or add per-row requests. Creation's domain Position contract is unchanged. Additive GET listing schema is explicit in OpenAPI. No migration/backfill/new dependency.

Acceptance defined before implementation:

1. Existing live employee change → independent approval → effective department, hire, rehire and position creation/filter workflows work with paginated selection. Fields keep existing accessible labels/IDs/error-summary links and unsaved values. Unknown/future department still fails existing backend validation.
2. Real PostgreSQL verifies search/selection and actual employment/position commands for references beyond1000, resolved listing names, tenant isolation and date validation. Existing architecture/regression tests pass.
3. Browser verifies shared picker next/previous/search/selected-value retention, empty/error/retry and stale-response cancellation. Real server outcome and replay/independent approval remain required. Exercise mobile/keyboard/axe/long content and failed reads. No mocked response counts as business completion.
4. Keep legacy API compatible for external readers; no Core HR UI continues to fetch its capped array. Verify through source inspection plus full workflow regression. Restore/recovered API checks include the position listing contract and all prior flags. Record local evidence, 45-source traceability and remaining release gates; no load/security/manual WCAG/production claim.

Consistency: use BA offset/date semantics and cancellation; each read rechecks server tenant identity. Application composition remounts on identity context change. No sensitive persistence/shared cache, no optimistic consequential mutation. UI state/labels only; rules stay backend-owned.
