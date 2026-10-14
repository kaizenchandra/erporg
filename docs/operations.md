# Operations and release gates

## Environments
Local compose binds PostgreSQL and Keycloak to loopback. `local` enables synthetic credentials; `oidc-demo` enables real OIDC with synthetic provider credentials. Neither may be combined with `prod`. Production requires APP_ORIGIN, DB_URL/USER/PASSWORD, OIDC_ISSUER/CLIENT_ID/CLIENT_SECRET from approved secret management; all are missing deployment decisions, not embedded live credentials. Test data is synthetic. Do not expose the local stack publicly.

Deploy topology: approved TLS ingress -> Next.js -> Spring monolith -> private PostgreSQL. Object storage arrives with DS-01. Dockerfiles are supplied, not evidence of successful image builds or scans. Provider dev mode/H2 is only for the identity sandbox; production IdP database, HA, backups and MFA enrollment are an infrastructure decision.

## Health and observation
`GET http://127.0.0.1:8080/actuator/health` and `/actuator/health/readiness` provide health. RequestGuard emits metadata only (correlation, status, elapsed milliseconds); no personal fields, payloads, credentials or bank data. Audit transaction records contain controlled personal changes; they are not telemetry. Spring's HTTP meter infrastructure is installed. Central collection, traces, dashboard deployment and alerts are **not yet verified**.

Proposed dashboards: request latency/errors by route template, DB pool saturation, pending approval age, denied requests, job backlog and failed integrations. Proposed alerts: 5xx >2%/5min, readiness down 2min, exhausted pool 5min, later-phase stuck outbox/payroll reconciliation. Run a synthetic alert drill before release. In-memory per-address request limits are local/single-instance controls (600 requests/minute, 30 form sign-ins/minute); configure distributed ingress limits and trusted proxy policy before scaling. JSON bodies <=32KiB, forms <=32KiB.

## Backup / restore runbook — local synthetic drill exercised
Run `python3 scripts/restore_check.py` while synthetic browser tests are idle. It is hardcoded to the local compose PostgreSQL service and `atlas_acceptance` schema, which must contain approved changes from previous browser tests. It dumps that schema in custom format, creates a unique `atlas_restore_<random>` database, restores with exit-on-error, and compares all table row counts and SHA-256 content digests. It checks request/version/audit references, migration success records, and proves the restored append-only audit trigger rejects an update. A second manifest confirms the probe left data unchanged.

No existing database is overwritten or dropped. The dump stays in process memory; only digests/counts and timing are written to `docs/evidence/restore-check.json`. The new database is retained for inspection. The recorded completed-workflow target is `atlas_restore_ae37d135a3274e1d`; an earlier schema-fixture drill also retained `atlas_restore_9437ae4322694998`. These contain only synthetic data. Review exact names before any later manual cleanup; never use wildcard deletion.

Executed drill: 31,372-byte dump, 13 employee rows, 67 requests (18 approved), 18 effective versions, 92 audit records, 27 notifications; all seven table digests matched. Restore command took 0.160 seconds; whole drill 4.919 seconds. No orphan records or failed migration records; restored trigger rejected modification. Six delivery-check tests pass, including failure on mismatched content and never creating a target after backup failure.

This is a small local logical restore, not encrypted offsite backup, point-in-time recovery or full service recovery. Source manifests are read before the dump; concurrent synthetic writes cause a safe comparison failure, so keep the fixture idle. Production requires a consistent recovery-point strategy, encrypted PITR/WAL shipping, residency/access decisions, IdP/object-store backups, infrastructure reconstruction, migration validation against the deployed artifact, application smoke tests and reconciliation. Production RPO <=15 minutes and RTO <=4 hours remain blocked/unmeasured.

## Deployment and rollback
Before deploy: all tests; signed/scanned digest-pinned artifacts; schema compatibility review; approved backup and restore evidence; staged smoke tests. Expand/contract migrations: add nullable structures first, deploy compatible readers/writers, backfill with resumable jobs, remove old columns only in a later release after observation. App rollback restores the prior artifact only if schema remains compatible. Database recovery restores PITR into an isolated cluster and reconciles subsequent writes; destructive down-migrations are not an assumed rollback.

## Incident
Restrict privileged actions, retain correlation/audit evidence, identify tenant/scope without copying PII into incident logs, notify accountable owner, revoke identity sessions as appropriate, restore only after reconciliation, record chronology and corrective controls. Live communications require separate authorisation.

## Release checklist
NOT approved for production. Missing: country/legal rules, identity lifecycle/revocation propagation, production MFA enforcement policy, residency/retention approval, large-data server pagination, delegated/multistage approvals, field breadth, CSP rollout, operational dashboards/alerts, load/failure testing, production recovery exercise, full ASVS review, dependency/container/secret/SAST closure, signed SBOMs and deployment rollback rehearsal. No performance, availability, RPO/RTO or WCAG conformance claim.

CI runs lint/typecheck/format/build, actual PostgreSQL tests, browser workflows and accessibility. Dependency job is deliberately failing on unaccepted high findings rather than silently ignoring them. npm audit found a development dependency chain (`braces` <=3.0.3 via micromatch/fast-glob and Next ESLint plugin), GHSA-vfj7-8cjw-p6xm. npm's suggested downgrade of eslint-config-next is not applied because it breaks version alignment. Requires upstream fix or reviewed alternative before release; no risk acceptance has been granted. Docker build/scans and CI remote execution have not been executed in this session.

Tooling support note: npm also marks inherited ESLint 9.39.5 as unsupported. It remains pinned for the current verified toolchain; migrate to a supported ESLint version compatible with the Next plugin and rerun checks before release.

## Dependency audit evidence
Run `python3 scripts/audit_dependencies.py` from the repository root. Exit 0 means no high/critical npm findings; exit 1 blocks on such findings; exit 2 means evidence could not be obtained or parsed. It writes full and runtime-only reports plus lockfile hash and UTC timestamp under `docs/evidence/dependencies`. An audit pass alone is never a release approval. CI preserves artifacts on failure and runs the separate Trivy step even when npm blocks. Review the advisory and supported dependency ranges before remediation; never force an incompatible downgrade solely to get a green audit.
