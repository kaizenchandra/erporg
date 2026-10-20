# Phase 2 increment AX: employee calendar assignment history

Prerequisites: AW's approved, immutable, episode-owned ledger (V61), current-record access policy and repeatable-read transaction boundary. HR-01 remains partial; SH-01/SH-05/SH-09 apply. No new dependency, migration, country rule or payroll consumer.

Workflow: employee, authorised current manager or HR opens the employee profile, expands calendar history and pages through approved assignments/unassignments across employment episodes. Existing date preview remains the authoritative applied result. Pending/rejected proposals are excluded. Original reasons, requester/reviewer identities and configuration provenance are not returned by this reference-history projection.

Architecture: thin GET adapter → EmployeeCalendarUseCases.history → current-record access check → application-owned EmployeeCalendarHistoryStore → module-owned JDBC adapter. A single repeatable-read transaction provides a consistent count/page and current episode. Core HR owns both joined tables. Query/writes remain tenant-scoped; the server derives tenant and access from authenticated identity. A bounded 1–50-row page and 0–100000 page index use stable episode/effective/recorded/version descending ordering. No cross-module table access.

Acceptance / planned exit evidence:

1. HR-01-D09: real approval creates a history row; independent unassignment retains both rows, including distinct business and recording dates. Pending/rejected proposals do not appear. Empty and out-of-range pages return an exact total without inferring assignments.
2. Current self/manager/HR scope is rechecked on every read; other employee, former manager and tenant are denied. Role/tenant headers cannot widen scope. Only public reference fields are returned, including canonical code/name/zone; no audit/private fields.
3. Future assignments remain labelled scheduled; older employment episodes remain distinct after actual rehire. History never implies an applied result; use date preview. Stable pagination handles same-day rows and exact integer bounds; no guessed backfill.
4. Profile view loads on demand, supports next/previous/retry/refresh, hides stale results on employee/version changes and cancels obsolete requests. Accessible status/error/focus and responsive cards, including long text, have browser/axe/visual evidence. No automatic retries for writes; this increment is read-only.
5. Java architecture/domain/PostgreSQL regression and live API contract/browser checks pass on packaged JAR and built Next. Record executed commands/results and limitations in verification/checkpoint/traceability. Existing restore manifests unchanged; verify this read against retained data without claiming a new full restore drill.

Visual direction: existing neutral cards, navy text and teal actions. Separate effective date, recorded UTC instant and employment episode labels; explicit “Unassigned” instead of blank codes. Eight rows per profile page, no bulk selection. Refresh is explicit. Future/historical labels include words, never colour alone.

Release boundary: all 45 source IDs stay in scope. This is approved company-reference history, not statutory policy, attendance, leave or payroll activation. Production release gates and reader-first rollback governance remain open. Next: governed legal-entity assignment and remaining lifecycle/benefits criteria.
