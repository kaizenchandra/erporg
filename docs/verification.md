# Phase 1 verification checkpoint — 2026-10-14

## Implemented vertical workflows
- Local synthetic role sign-in plus real Keycloak OIDC code flow; privileged synthetic TOTP and provider logout verified separately.
- Role/tenant-scoped directory: search, filters, sort, bounded pagination, selection and location-column control; mobile employee cards.
- Profile, employment/contact read model and authorised audit history.
- Phone-change submission -> independent approval/rejection -> PostgreSQL effective-dated version -> profile as-of current UTC date -> immutable audit and requester in-app notification.
- Manager job-title/department/location proposal UI and backend scope policy; full transfer/promotion lifecycle remains Phase 2.
- Idempotent submission, conflicting-key rejection, stale-version checks, single-winner concurrent approvals, CSRF, body limits, local rate limits, safe request metadata logs and health/readiness.

No full source requirement is claimed complete across the entire ERP scope. Phase 1 **increments** cover HR-01, HR-03, HR-04, SH-01, SH-02, SH-03, SH-05; separate frontend/backend/criterion statuses and exact evidence pointers are in traceability.json. The inventory is 45 source IDs and 356 decomposed criteria. Later navigation is explicitly roadmap content.

## Executed evidence
| Gate | Executed result |
|---|---|
| `npm ci --ignore-scripts` | Passed, lockfile resolves; npm audit reports five high development-tool dependency findings |
| `npm run lint` | Passed |
| `npm run typecheck` | Passed; strict TypeScript |
| `npm run format:check` | Passed |
| `npm run build` | Passed; Next.js 16.4.0 production build |
| `mvn ... spotless:apply test` | Passed: 15 Java tests (3 architecture, 4 domain, 8 actual PostgreSQL integration) |
| `npx playwright test` | Passed: seven browser/API tests; one OIDC test explicitly skipped in local mode and passed separately against Keycloak. |
| `OIDC_TEST=1 npx playwright test tests/oidc.spec.ts` | Passed: actual browser OIDC login, required synthetic HR TOTP, server subject mapping, provider logout and subsequent 401 |
| axe WCAG tags | No violations in tested directory, approval inbox, profile and invalid-form states; not a WCAG certification |
| Visual inspection | Desktop directory/profile and 390px mobile screenshots opened and inspected; no mobile horizontal overflow in tested journey |
| SBOM | `npm sbom --sbom-format=cyclonedx` generated frontend-sbom.json |

Evidence screenshots: directory-desktop.png, profile-desktop.png, mobile.png, approval-inbox.png and oidc-mfa.png in `docs/evidence`. Machine Java reports are in `backend/target/surefire-reports`; Playwright reports in `playwright-report` (generated, gitignored). Synthetic schemas `atlas_test` (rollback tests) and `atlas_acceptance` (browser tests) isolate test runs from the default workspace. Browser tests intentionally persist synthetic commands and audit.

Machine-readable checkpoint: [evidence/checkpoint.json](evidence/checkpoint.json). Final API rerun on the last backend revision: both security/concurrency tests passed, including malformed-body 400 and matching error/header correlation IDs.

## Findings and limits
The initial password encoder mismatch was caught by browser tests and fixed. Type mismatches from two installed Playwright-core versions were fixed with a pinned shared version. A custom CSS loader served stale styles and caused build-worker port failures; native CSS removed that dependency and the production build passed. Form contrast, filter/decision labels, session-reset state and expired-CSRF recovery were corrected and checked.

Open security finding: GHSA-vfj7-8cjw-p6xm in braces via the ESLint toolchain, represented by five npm audit high findings. No accepted waiver. Production dependency audit, SAST, secret scan and container scan evidence are not yet available. Container files and CI definition are supplied but image builds / remote CI execution are not claimed.

Not measured: latency under the NFR workload, Web Vitals p75, availability, RPO/RTO, restore, production rollback and migrations, large-data performance, assistive-technology review. Only local schema creation/Flyway validation is evidenced. Real legal entities, countries/payroll rules, production IdP policy, hosting/data residency and retention ownership remain decisions. These block production release, not local development.

Remaining Phase 1 hardening: durable session revocation across multiple instances, CSP rollout, field-specific inline validation/error summaries beyond native controls and server summary, saved filters/draft persistence and broader manual a11y. These are explicitly open rather than hidden behind a production-ready label.

Next implementation: Phase 2 full personal/emergency/employment records and effective-dated organisation/reference models; lifecycle and benefits; private scanned documents; configurable calendars/localisation. Complete missing Phase 1 hardening and organisation-wide auth policies alongside this work. Production release decision: **NOT READY**.

## Continued Phase 1 boundary hardening — 2026-10-14
Employee/request list access is now resolved in `PeopleService` into an explicit `PeopleReadScope`. JDBC translates the resulting tenant, self and direct-report constraints without inspecting roles. Invalid or incomplete identity contexts fail closed. ArchUnit prohibits outbound adapters from calling Actor role/policy methods, with a deliberately invalid persistence fixture proving that verification fails.

Executed after this change: Java 21 `mvn -f backend/pom.xml -Dmaven.repo.local=/private/tmp/atlas-m2 spotless:apply test` passed all 13 tests against PostgreSQL; `npx playwright test` passed all six local browser/API tests in 8.4 seconds, with the OIDC-only test skipped. OIDC evidence remains the earlier separate run, not a new run. No frontend or database schema change was needed. Backend restarted against `atlas_acceptance` for the browser run. Evidence: `docs/evidence/java-boundary-verification.txt` and the generated Surefire/Playwright reports.

This closes the identified persistence-layer role-policy issue. Existing Phase 1 gaps, five previously recorded high development dependency findings, and production release blockers remain open. No source requirement was promoted to complete.

## Server directory pagination — 2026-10-14
`GET /api/v1/directory` now performs authorised search, department/status filtering, stable name/ID sorting and page selection in PostgreSQL. It returns scoped totals and department options. Query bounds: zero-based page ≤100000, page size 1–100, search/department ≤120 characters, status ≤40 characters; direction accepts asc/desc only. Search treats SQL wildcard characters literally. The directory UI uses this endpoint, debounces search by 150 ms, cancels superseded reads, hides stale result rows, announces loading and offers retry on error. Selection remains explicitly selected IDs, never all filtered employees.

Executed: 15 Java tests passed, including an isolated 1005-row fixture proving the final page is reachable beyond the old cap; seven browser/API tests passed (11.1 seconds), OIDC-only test skipped. Browser checks include page navigation, filters, accessible profile/approval workflows and query validation/authorisation. Lint, strict typecheck, formatting and production build passed. Test fixture changes roll back. See `docs/evidence/java-directory-verification.txt`.

Limits: existing `/employees` bootstrap, dashboard metrics and organisation preview still use the capped 1000-row read model; those are not large-workforce reporting evidence. Directory count, page and facets use separate read-committed statements, so concurrent changes can temporarily make totals differ from page contents; refresh reconciles them. Offset queries are bounded but load latency is not benchmarked. No NFR performance or full HR-01 completion claim is made.

## Uncapped workspace totals and direct profiles — 2026-10-14
Workspace bootstrap no longer downloads `/employees` (the legacy 1000-record endpoint). It loads `/people-summary` for uncapped authorised employee/status/request counts, `/employees/{id}` for the authenticated user's profile, and a 100-row directory page for the explicitly labelled organisation preview. Department and status totals are computed in PostgreSQL using application-owned query scope. Direct profiles enforce tenant and record policy before returning employee data.

Executed: 15 Java tests passed; the rollback-isolated 1005 additional employee fixture now asserts 1017 total employees, a one-person self scope, profile retrieval beyond the old cap, and cross-record/cross-tenant denial. Seven local browser/API tests passed; the OIDC-only test remains skipped in this local configuration. Lint, strict type checking, formatting and production build passed. Evidence: `docs/evidence/java-summary-verification.txt`.

This supersedes the previous dashboard-cap limitation. Remaining bounds: organisation is visibly a first-100 preview; approval inbox still retrieves at most 500 recent requests even though the pending count is now uncapped. The legacy employee endpoint remains available for compatibility/testing. Full organisation navigation and approval pagination remain open; these are not complete SH-04 reporting or HR-02 implementations. Production release remains NOT READY.

## Approval inbox pagination — 2026-10-14
The UI now uses `/api/v1/request-inbox` with server-side status filtering, stable recorded-time/ID ordering, authorised totals and 12-row pages. Workspace startup no longer downloads the legacy capped request list. Reads have a 10-second deadline, cancellation, stale-page suppression, loading/error states and explicit retry. Filter changes and successful decisions reset to page one; decisions still require confirmed server outcomes. The prior 500-row UI limitation is resolved; the legacy endpoint remains for compatibility.

Executed: 16 Java tests passed (3 architecture, 4 domain, 9 PostgreSQL integration), including 505 extra synthetic requests proving the last page of 507 pending requests is reachable and outsider counts remain zero. Eight local browser/API tests passed in 12.2 seconds; one OIDC-only test skipped. New browser coverage creates 13 synthetic requests, navigates pages and verifies filter reset. Existing approval/rejection, concurrent decision, accessibility and session-expiry checks pass. Lint, strict types, formatting and production build passed. Java evidence: `docs/evidence/java-inbox-verification.txt`.

Remaining: delegation and ordered multistage approvals are not implemented. Counts describe visible submitted requests, including the user's own, rather than exclusively actionable review tasks. Separate read-committed count/page queries may differ during concurrent writes; refresh reconciles results. Offset pagination performance is not load-tested. Organisation remains a labelled first-100 preview. No source ID was promoted to complete; production release remains NOT READY.

## Dependency gate and traceability preservation — 2026-10-14
Executed `python3 scripts/audit_dependencies.py`: exit 1 / BLOCKED, with five high findings in the full npm dependency tree and zero findings in the runtime-only npm audit. Both raw reports and a timestamped package-lock SHA-256 summary are in `docs/evidence/dependencies`. The gate exits 2 for unavailable/malformed reports and removes stale report files rather than presenting old results as current. Runtime-only npm results do not cover Java dependencies, container images, application flaws or development-tool exposure.

The GitHub advisory GHSA-vfj7-8cjw-p6xm still lists no patched braces version (https://github.com/advisories/GHSA-vfj7-8cjw-p6xm). Registry queries show braces 3.0.3 remains latest. ESLint 9 is end-of-life (https://eslint.org/version-support/), but the current eslint-plugin-react 7.37.5 and eslint-plugin-jsx-a11y 6.10.2 peer ranges exclude ESLint 10. No forced upgrade, incompatible Next config downgrade, override or waiver was applied. This remains a release blocker requiring an upstream fix or a separately evaluated supported lint-stack migration.

CI now invokes the evidence-preserving audit gate, uploads reports even when blocked, and attempts its existing Trivy check independently of npm audit failure. Remote CI and Trivy execution are not claimed. The audit gate was executed locally against the registry.

Fixed traceability regeneration dropping newly added evidence metadata. Executed `python3 -m unittest discover -s scripts -p 'test_*.py'`: four tests passed, covering clean audits, finding-induced failure with report preservation, malformed-response failure with stale-report removal, and all 45 source rows/criterion statuses/evidence surviving regeneration in a temporary directory. Application code/dependencies did not change; previous workflow evidence remains historical and was not rerun in this step.

## Server-owned field validation and accessible recovery — 2026-10-14
Employee-change validation now returns all applicable field errors in one 400 response (`fieldErrors`) while keeping rules in the Java domain. The error response does not echo invalid values. The UI associates each message through aria-describedby/aria-invalid, focuses a named error summary and provides keyboard-operable links to invalid fields. Entered values are preserved. Date/field-only edits now trigger the unsaved-change warning too. Ordinary transport/conflict errors remain summary errors; decision forms still use their existing validation path.

Executed: 17 Java tests passed, including multi-field aggregation and immutable/no-input-echo messages. Eight local browser/API tests passed in 12.3 seconds; OIDC-only test skipped. Direct API tests verify field keys, safe errors and unchanged employee version on invalid submission. Browser tests verify summary focus, link-to-field focus, preserved values, session-expiry clearing and axe checks. The first browser runs exposed count-badge and inline-error contrast failures; both were corrected and the full suite passed. Lint, strict types, formatting and production build passed on the final revision. Java evidence: `docs/evidence/java-validation-verification.txt`.

This closes the identified employee-change form field-error gap, not comprehensive WCAG conformance or all form requirements across later modules. Dependency gate remains blocked; production release remains NOT READY.

## Local recovery drill — 2026-10-14
Executed `python3 scripts/restore_check.py` against idle synthetic acceptance data. Backup and restore to a newly created database passed: seven table counts and SHA-256 row digests matched; 18 approved requests/effective versions and 92 audit records were preserved; no orphan requests/versions/audit entries or failed migration history rows. The restored audit trigger actively rejected a modification, and a final manifest confirmed unchanged content. Whole drill 4.919 seconds; restore command 0.160 seconds. Evidence: `docs/evidence/restore-check.json`.

Executed six delivery-check tests, including safe backup-failure handling and detection of a mismatched restore. The script never drops or replaces databases. Both uniquely named local drill targets remain for inspection (operations runbook lists them). No application code changed in this step, so browser/Java workflow results remain the preceding checkpoint rather than a new run. Production RPO/RTO remains unmeasured; PITR, encrypted offsite storage, infrastructure rebuild, full-service/IdP recovery and application-level smoke testing on the restored environment remain open.
