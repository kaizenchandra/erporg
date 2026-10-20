# Phase 2 increment BA: complete department reference browsing

Prerequisites: Core HR immutable department catalogue and current tenant identity. HR-02 browsing, SH-01 scoped access, SH-06 references and SH-09 date presentation apply; SH-05 existing creation audit remains authoritative. This read increment does not complete hierarchy maintenance or any source ID.

Problem: legacy department array returns at most1000 rows. UI resolves parents inside that array and can mislabel an unseen parent as a root. Add an authenticated tenant-scoped `/api/v1/organisation/department-directory` with bounded search, current/planned filter, root/direct-child filter, name order and page/size. Resolve parent names and direct-child counts from owned tables before page output. Use one injected UTC date and bounded repeatable-read transaction for count/items/date consistency. No migration or historical rewrite. Keep the legacy array for compatibility; remaining legacy pickers stay explicitly bounded until migrated.

UI: paginated reference cards with persistent filters, exact parent code/name, cost-centre demonstration label, evaluated date and server status. View direct departments and clear navigation without losing the organisation workspace. Creation refreshes/searches the confirmed new code; creation parent selection uses the same bounded server search and preserves selected code while paging. Separate catalogue failure from legacy creation audit failure. No bulk action or export.

Acceptance before implementation:

1. Actual PostgreSQL tests retrieve/search records beyond1000 and a parent outside the current page, exact tenant counts, roots/direct children, CURRENT/PLANNED at an injected UTC boundary, both orders and invalid query bounds. No employee data is returned; other tenant references are excluded and client tenant/role hints never widen access.
2. Browser verifies real create parent/child → search child → correct parent → direct-child navigation, scoped count/page/status/sort/reset, empty/error/retry and cancelled obsolete responses. Keyboard/mobile/axe/visual checks use actual API, not mocked completion. Error injection only verifies recovery.
3. Existing creation/transfer/position workflows, contracts, architecture boundaries and full regression pass. New contract and negative fixtures cover query/response shapes. Restore checker compares complete authorised catalogue IDs/parent/count/date across supported local identities; no sensitive values in evidence.
4. Update all45-source traceability, separate frontend/backend evidence, NFR scope and checkpoint. Hierarchy editing/reorganisation, cost-centre governance/finance and remaining legacy pickers stay open. No latency/capacity, full accessibility/security or production-readiness claim.

Consistency: immutable references, deterministic name/code order and per-read snapshot; offset pages can shift when others create references. Refresh resets page. No cross-request snapshot promise. Provisionally size1–50, page0–100000, search≤120 and exact parent code≤30. No new dependencies/topology.
