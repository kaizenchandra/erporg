# Phase 2 increment BD: independent benefit history recovery

Prerequisites: existing authenticated benefit catalogue, HR-only immutable creation-history API and confirmed plan creation. Scope: HR-06, SH-01 and SH-05 presentation resilience. No API, domain, dependency or migration changes.

Acceptance defined before implementation:

1. Plan browsing loads independently from history. The HR history request starts only when its disclosure opens. A history failure cannot hide plans; a plan failure cannot hide the history disclosure. Employees and managers have no history disclosure, and existing direct API enforcement remains authoritative.
2. History has its own loading, empty, error, retry and refresh states. Failure receives focus; successful retry/refresh moves focus to the history heading. Requests have a bounded timeout. Closing or identity/tenant/revision unmount aborts requests, discards private payload and resets state; late responses cannot replace the reopened view.
3. A confirmed plan creation refreshes plans and remounts history, avoiding stale audit payload. Show actor, reason, UTC recording time, separate effective date and optional correlation disclosure. Preserve the explicit latest-100 limitation; complete benefit-journal pagination remains unimplemented, rather than implying full retrieval.
4. Execute strict frontend checks/build, the existing benefit creation/eligibility and API permission/tenant tests, and browser failure/cancellation/non-HR/mobile accessibility checks against the running API/database. Injected failures demonstrate UI recovery, not backend availability or business completion. No new production readiness, performance, recovery, ASVS or full WCAG claim.

Exit evidence and any failures are recorded in verification and traceability. All 45 source requirements remain in scope; no parent requirement is completed by this increment. Full benefit-history retrieval, benefit policies/payroll consumption and later phases remain planned.
