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
