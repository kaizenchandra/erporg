# Phase 5 closure increment

Scope: TM-02 new-hire federated access, TM-05 authoritative compensation/payroll consumption, and talent's SH-01–SH-10 controls. Full Phase 5 signoff is open until evidence below is recorded. No production readiness is asserted.

## Decisions and exit criteria

- Identity-provider accounts remain owned by the configured OIDC provider. HR binds an exact verified `sub` to an approved new-hire employee; another HR employee must approve. Never match by an unverified email, auto-grant HR, resurrect a rehire, or create production credentials. Access begins on the recorded employment date, with issuer checks and existing session-epoch/termination controls. Acceptance: persist/reload/request/review through UI/API, real local provider code-flow login and logout by that new employee, forbidden HR/cross-worker access, start-date, alias independence, stale revision, issuer mismatch, replay and concurrent-review tests.
- Payroll owns effective compensation. Talent must use the authoritative salary and immutable revision rather than trusting a typed current salary. Approved proposals must create auditable salary/bonus inputs consumed by an actual payroll calculation, retaining salary/rule/input snapshots and exactly one bonus reservation. Existing hourly calculations remain reproducible. Country statutory rules remain unconfigured.
- Shared controls must be assigned and verified individually: approval inbox/delegation (SH-02), committed notifications/reminders/outcomes (SH-03), scoped reports/exports (SH-04), immutable audit (SH-05), policy configuration (SH-06), migration (SH-07), handoff reconciliation (SH-08), currency/date/localisation (SH-09), privacy/retention/legal hold (SH-10). None is complete solely because a status appears on a screen.

## Identity implementation

V84 adds immutable initial-binding requests and independently recorded decisions. Approval and Employee membership are one transaction with a deferred reciprocal database integrity check. Existing employment access rejects a binding before its start date and rejects reassignment to another tenant, employee, or privileged role. The security boundary compares the authenticated OIDC issuer at login and on each request; local-password authentication cannot impersonate a federated binding. Binding does not activate employment, provision the provider account, or claim production-provider readiness.

Configure `INITIAL_IDENTITY_ISSUER` for local testing; OIDC profiles default to their configured provider issuer. The UI is in Talent → Onboarding → selected joining plan → New-hire SSO binding. There is no change to existing rehire/replacement controls. Requests retain a bounded latest-20 history per employee; deeper operational export remains a shared-control gap.

References inspected: [Keycloak OIDC discovery, code-flow and logout endpoints](https://www.keycloak.org/securing-apps/oidc-layers), [Keycloak Admin REST API](https://www.keycloak.org/docs-api/latest/rest-api/index.html). Local provider fixtures are development-only, not a vendor integration or production provisioning implementation.
