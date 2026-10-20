# Phase 2 increment BH: exact benefit decision versions

Prerequisites: existing independent HR benefit decision, row lock, immutable audit and unique pending payroll handoff. Scope HR-06, SH-02 and SH-05. No schema, contribution rule, provider or payment change.

Acceptance defined before implementation:

1. Preserve a JSON version's decimal value at the inbound HTTP boundary. Reject missing, null, negative, fractional and signed-64-bit overflow values with HTTP400 and a version validation error before invoking the application command. Do not silently truncate or round. Integral decimal/exponent representations are accepted when exactly representable; stale exact versions continue to return409.
2. Keep the framework-free application command's Long contract and existing role/tenant/independence, row locking, terminal states and idempotent replay rules. No global JSON coercion change to unrelated APIs.
3. Real HTTP/PostgreSQL regression must prove invalid values leave the pending row/version, audit count and handoff count unchanged; subsequent exact approval creates precisely one decision audit and handoff. Identical replay remains stable; fractional replay fails400 and conflicting exact replay fails409. Exercise rejection and access denials as well.
4. Browser verification uses the actual approval form and API, including a rejected fractional transport, preserved pending state, successful exact decision and retained status. Run the complete Java suite and relevant browser/API checks, update the OpenAPI range and traceability, and distinguish this checkpoint from the prior full-browser/recovery evidence.

Initial non-mutating probe on a terminal synthetic selection returned409 for version0.5, showing it reached application state handling. A pending selection regression will establish the consequence in an isolated test schema. All45 source requirements remain in scope; Phase2 and production readiness remain partial/NOT READY.
