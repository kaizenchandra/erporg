# Atlas · People & Operations

A Next.js + Java/Spring Boot + PostgreSQL modular monolith. Phase 1 implements a synthetic, authenticated employee directory/profile and employee-change approval workflow. All **45** source requirements remain tracked across seven delivery phases. This is **not a production-ready ERP**.

## Start locally

Prerequisites: Node 24.19.0, Java 21, Maven 3.9+, Docker Compose, Chrome (or Playwright Chromium).

```sh
npm ci --ignore-scripts
docker compose up -d --wait postgres
# Terminal 1: use Java 21 (Maven may otherwise pick a newer JVM)
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
# Terminal 2
npm run dev -- --hostname 127.0.0.1
```

Open **http://127.0.0.1:3000**. Select Amelia (HR), Olivia (independent HR reviewer), Marcus (manager), or Sophia (employee). Synthetic local password: `atlas-demo` (override backend with LOCAL_DEMO_PASSWORD and enter it in the form). All local identities/data are fictional. Java on this Mac: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`. The implementation session used `-Dmaven.repo.local=/private/tmp/atlas-m2`; normal installations may use their default Maven cache.

Workflow: sign in as Sophia -> Request a change -> contact number/reason/effective date -> submit -> sign out -> sign in as Amelia -> Approvals -> review old/new value and impact -> approve with comment -> People -> Sophia -> inspect current contact and audit history. Future-dated changes stay pending in the current profile until the date; decisions are final and repeated approval returns 409. Old proposals must be rejected after an intervening approved change. A later effective change blocks backdated insertion; explicit corrections follow in Phase 2.

HR sees all tenant records; managers see self/direct reports; employees see self. Contact changes are allowed within those scopes; employment changes require HR or a reporting manager. No one can approve their own request or a change to their own record. Tenant and role come from server membership, not request headers.

## Real local OIDC / MFA sandbox

```sh
docker compose --profile oidc up -d keycloak
# Wait for realm import; stop the local-profile backend first.
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=oidc-demo
```

Use the same app URL and **Sign in securely**. Keycloak is pinned to 26.8.0. Amelia's sandbox login requires TOTP; the test-only authenticator secret in the realm fixture is `JBSWY3DPEHPK3PXP` (SHA-1, 6 digits, 30 seconds). Marcus/Sophia use password-only sandbox sign-in. This fixture demonstrates MFA capability, not organisation-wide production MFA enforcement. No credentials/tokens are stored in browser storage. Session cookies are HttpOnly; production uses Secure and OIDC-compatible SameSite=Lax. OIDC logout uses the provider end-session endpoint; remote revocation propagation remains a release gate.

## Verify

```sh
npm run lint
npm run typecheck
npm run format:check
npm run build
mvn -f backend/pom.xml spotless:check test
# local,acceptance-profile backend and frontend running:
npm test
# oidc-demo backend + Keycloak running, separate suite:
OIDC_TEST=1 npx playwright test tests/oidc.spec.ts
```

Browser tests use installed Chrome on macOS; set CHROME_PATH for another executable, or use CI=true with `npx playwright install chromium`. Unit/integration tests use isolated PostgreSQL schema `atlas_test` and rollback their writes. Browser tests persist synthetic changes; run against a clean dedicated schema for repeatable acceptance. Example backend arguments: `-Dspring-boot.run.arguments='--spring.datasource.url=jdbc:postgresql://localhost:5438/atlas?currentSchema=atlas_e2e --spring.flyway.default-schema=atlas_e2e --spring.flyway.schemas=atlas_e2e'`. Do not run these tests against real employee data. Tests requiring OIDC are explicitly skipped in the local suite.

## Project map

- `features/people`: directory, profile/forms, approvals, organisation view and workspace data.
- `components/ui`: accessible primitives and consistent SVG icons.
- `lib`: typed API client/contracts, secure session handling.
- `backend/.../modules/corehr`: domain, use cases/ports, HTTP/JDBC adapters, composition.
- `backend/.../configuration`: security and transport controls.
- `backend/.../resources/db`: immutable versioned schema plus separately enabled synthetic seed.

Read [architecture](docs/architecture.md), [design](docs/design.md), [traceability](docs/traceability.md), [behaviour criteria](docs/traceability.json), [roadmap](docs/roadmap.md), [NFR register](docs/nfr.md), [threat model](docs/security.md), [API contract](docs/openapi.json), [operations](docs/operations.md), and [verification checkpoint](docs/verification.md).

Directory/API lists are intentionally capped at 1,000 employees / 500 requests in this increment; UI filtering/sorting/paging operates on this bounded authorised set. Real server pagination, full personal/emergency data, draft saving, saved filters, delegation and advanced workflows remain explicitly open. Later module navigation shows roadmap information, not fake working modules. Public deployment, production migrations and live payments require an authorised release step.

Delivery checks: `python3 -m unittest discover -s scripts -p 'test_*.py'`. Refresh dependency evidence with `python3 scripts/audit_dependencies.py`; it currently exits 1 for five high development-tool findings. Full/runtime audit reports and the lockfile fingerprint are saved under `docs/evidence/dependencies`, including when the gate blocks.

Local synthetic recovery drill: after browser acceptance tests have created approved changes, stop test activity and run `python3 scripts/restore_check.py`. It creates and retains a new isolated database and writes `docs/evidence/restore-check.json`; it never overwrites existing databases. See `docs/operations.md` for scope and production recovery gaps.

To verify the application against that restored database, run `python3 scripts/restore_application_check.py` with Java 21 selected. It starts a temporary API on loopback port 8081, verifies authenticated read workflows and unchanged data, then stops that process. Existing services and databases are preserved. Evidence is saved to `docs/evidence/restore-application-check.json`.

Phase 2 private-contact increment: employees can request home-address or emergency-contact changes through the existing approvals; HR reviews them. Team-manager responses redact these fields and exclude their private approval/audit data. Run `npx playwright test tests/private-contact.spec.ts` to verify the real UI workflow. This is a subset of Core HR; broader Phase 2 scope remains tracked.

Phase 2 department increment: open People → Organisation view to create a department as HR, configure its start/parent/cost-centre code, and inspect its audit. Managers request transfers by selecting those references in the employee-change form. Run `npx playwright test tests/departments.spec.ts` for the creation-to-transfer-to-approval workflow. Seeded cost-centre codes marked DEMO are provisional reference mappings; payroll/finance costing is still planned.

Hiring increment: sign in as Amelia, open People → Hiring and submit a hire using a configured department and existing manager ID. Sign in as Olivia to approve/reject it; approval opens the assigned employee in Onboarding. Payroll/access provisioning remain pending. Run `npx playwright test tests/z-hiring.spec.ts` for this workflow. The full browser suite should use backend profiles `local,acceptance`; the synthetic acceptance profile has bounded higher request budgets and cannot be combined with prod.

Manual onboarding increment: as HR, open People → Hiring → Approved → Complete onboarding, inspect the employee/impact, and confirm with a reason on or after the start date. Open employee to inspect Active status and scoped employment history. The confirmation is effective on its UTC business date; it does not automatically provision payroll or identity accounts. Run `npx playwright test tests/z-onboarding.spec.ts` for UI/API regression. See `docs/verification.md` and `docs/evidence/checkpoint.json` for executed evidence and remaining gates.

To remove an obsolete home-address or emergency-contact field, request a change, choose that field and select “Remove the current value.” Supply reason/date; independent HR reviews. Approval shows Not provided, while authorised history retains the earlier value. Run `npx playwright test tests/private-contact.spec.ts`. This is not deletion of historical records.

Position catalogue: People → Organisation view → Create position (HR). Configure code, title, department, start date and reason. All tenant members can search, filter by department, sort title and navigate server pages. Creation does not assign employees; position assignments remain planned. Verify with `npx playwright test tests/positions.spec.ts` and Java PositionWorkflowTest.

Primary assignment: as a team manager or HR, open an employee → Request a change → Primary position / assignment. Find/select a configured position and supply date/reason; an independent reviewer decides in Approvals. Position department must match at that date and all affected future dates. To transfer an assigned employee, first request and approve explicit unassignment. Profile/history show the effective position; job title, pay and manager are separate. Verify with `npx playwright test tests/position-assignment.spec.ts`.

Reporting changes: manager/HR opens employee → Request a change → Reporting manager / team change. Find/select a configured manager and provide reason/effective date; independent HR reviews in Approvals. Team access changes on that date. Candidate eligibility currently requires an HR/MANAGER membership; no role is automatically granted. Verify with `npx playwright test tests/reporting.spec.ts`. Root removal, delegation, matrix reporting and bulk reorganisation remain planned.

Display-name maintenance: as employee or HR, open the profile → Request a change → Employee display name. Supply proposed value, date and reason; independent HR reviews in Approvals. Current directory/profile/workspace labels follow the approved business-effective date. Employee ID/email stay stable; legal identity and provider profile sync remain planned. Verify with `npx playwright test tests/display-name.spec.ts`.

Pending changes can be withdrawn by their requester: Approvals → pending card → Withdraw request. Review one-request impact and enter reason; the Withdrawn filter shows the outcome. Employee values/version stay unchanged. Approved/rejected requests require their separate correction process. Verify with `npx playwright test tests/withdrawal.spec.ts`. Submission APIs require an explicit version; matching command replay works across an effective-date boundary.

Benefits catalogue: People → Benefits. HR can create immutable demonstration plans with currency, fixed monthly contribution, service days, start and reason. Employees can filter eligible plans using their own employment snapshot; previews are labelled DEMONSTRATION. No enrolment, dependant record or payroll deduction is created. Verify with `npx playwright test tests/benefits.spec.ts` and BenefitsDomainTest/BenefitsWorkflowTest. Company/country/entity policy validation and remaining HR-06 workflows remain open.

Benefits selections (Phase 2 demonstration): sign in as Sophia, open Benefits, select an eligible plan, optionally add up to five dependant names/relationships, and submit a reason. A different HR employee reviews the selection in Benefits. Approval persists a fixed monthly contribution snapshot and one `PENDING_PAYROLL` handoff; no provider enrolment, payroll processing or payment occurs. HR cannot approve their own selection. V20/V21 retain immutable history; HR-06 remains partial pending policy validation and real payroll consumption.

Targeted verification: `npx playwright test tests/benefit-selections.spec.ts`; Java `BenefitSelectionWorkflowTest` uses actual PostgreSQL and architecture checks run with the existing Maven suite. Full startup/test commands above apply.

Private documents (Phase 2 subset): start the local scanner with `docker compose --profile documents up -d --wait --wait-timeout 180 postgres document-scanner`, then use the existing Java/Next startup commands. In People → Documents, upload your synthetic UTF-8 `.txt` (≤16 KiB) with a reason. Clean files can be downloaded privately; rejected/quarantined files are blocked. Employees/managers see their own documents; HR sees the tenant catalogue. `npx playwright test tests/documents.spec.ts` exercises the real UI/API/scanner. Java tests now require the scanner as well as PostgreSQL. On Linux configure `-Datlas.documents.local-root=/tmp/atlas-private-documents` (CI does so); the default macOS development root is `/private/tmp/atlas-private-documents`.

DS-01 remains partial: initial text format only; targeted policies, independent approval, retention/legal holds, production object storage/encryption and scanner freshness remain open. Version history, scan retries, templates/completeness and maintenance are described below. The local adapter is not enabled in production. Recovery commands/evidence are documented in `docs/operations.md` and `docs/verification.md`.

Quarantined-document retries: HR can retry a stored file up to three times with a reason. The original upload/scan remains immutable; latest committed scan controls downloads. Stable command replay avoids extra scan results; competing/stale retries conflict. Clean/malware outcomes are terminal. Run `npx playwright test tests/document-retry.spec.ts` sequentially: it briefly stops/restarts this project's local scanner to verify actual outage recovery. Do not run Java scanner tests concurrently. Targeted template policies, retention/legal holds and production storage/freshness remain incomplete.

Document version history: People → Documents → Upload new version. Enter a replacement text file/reason; prior versions remain immutable and separately scanned. Catalogue shows latest upload even when blocked; expand Document versions for explicit authorised historical downloads. Run `npx playwright test tests/document-versions.spec.ts`. Versions count toward the 100-file employee guard. DS-01 remains partial: targeted policies, retention/legal holds and production storage/encryption/scanner freshness remain open.

Templates and completeness (Phase 2 subset): HR opens Documents → Templates and completeness → Create document template; provide code/title/plain text, required flag, effective date and reason. This creates immutable tenant-wide company configuration, not a legal requirement. Employees/HR use a clean document card → Link to template to classify its chain. Latest clean evidence shows PRESENT; no link is MISSING; rejected/quarantined latest is BLOCKED. Future and optional configuration are separate states. HR may check another authorised employee ID; employees check only themselves. Run `npx playwright test tests/document-templates.spec.ts`. Dated revisions/retirement and HR reclassification are described below. Targeting, unlink, content approval, retention/legal holds and production document controls remain open; DS-01 stays partial.

Template maintenance: expand a template's revision history; HR can append a dated revision or terminal retirement with reason. Effective configuration drives completeness; future and prior snapshots remain in history. HR can correct an existing requirement's classification from a new clean document card → Correct classification. Earlier files/links stay reserved and audited; no unlink/delete/reactivation is provided. Verify with `npx playwright test tests/document-maintenance.spec.ts`. Targeted rules, independent approval and retention/legal holds remain planned; production is not ready.

Retention and legal holds (local Phase 2 subset): HR opens a document card → Retention and legal holds. Record an explicit company policy basis/retain-until date or a case hold with reason. A different HR subject and employee must release each hold; multiple holds remain independent. Dates may only extend. The entire chain, including future versions, shares one private immutable journal. NO POLICY/RETAIN/REVIEW DUE/ON HOLD are review states; deletion is always unavailable. Verify with `npx playwright test tests/document-retention.spec.ts`. No statutory period, automatic deletion, archive/purge, financial-history rule or production legal compliance is provided.
