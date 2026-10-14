# Threat model and privacy
Baseline: OWASP ASVS 5.0 Level 2 proposed scope (https://owasp.org/www-project-application-security-verification-standard/), no certification claim.

| Threat | Control | Release evidence required |
|---|---|---|
| Employee enumeration / tenant spoofing | Server-owned identity membership; every query tenant-scoped and record policy | Cross-tenant and direct-object negative tests |
| Forged or replayed approvals | CSRF, independent approver, version lock, idempotency | Concurrent approval and duplicate tests |
| Privilege escalation | Deny by default, allowlisted fields, no client role trust | Employee/manager/HR API matrix |
| Session theft | HttpOnly, same-site cookies, TLS, session expiry/logout | OIDC/MFA/revocation integration test |
| Injection and XSS | Prepared SQL, React output encoding, bounded values | Validation and static scan |
| Private data in logs | No request payload logging; safe errors and correlation IDs | Log inspection |
| Document malware / disclosure | Phase 2 private storage, size/type validation, quarantine scan and authorised download | Malware and access tests before DS-01 exit |
| Exports / admin abuse | Scoped asynchronous bounded exports, audit, segregation of duties | Phase 7 export/privacy verification |

No real employee data in local builds. Personal/contact records: configured retention after employment; employment and payroll history: statutory retention pending jurisdiction; financial records: archival and correction, never generic deletion; documents: category-specific retention; legal hold overrides deletion. SH-10 will implement authorised access requests, policy decisions and anonymisation with immutable decision audit. Retention durations require accountable legal owner; no invented statutory periods.

Production prerequisites: managed TLS, encrypted database/backups/object storage, external secrets, OIDC audience/issuer validation, privileged MFA, revocation integration, rate limiting at ingress, bounded request bodies, deployment-specific CORS (same origin by default), tested CSP, security/dependency/container/secret scans. Local authentication is not evidence of OIDC readiness.

JDBC session hardening: PostgreSQL now persists sessions with a 30-minute idle timeout and HttpOnly cookie, with Secure enabled in production configuration. Tested logout revokes a copied cookie across two API instances; expiry and application restart behavior were exercised. Persisted attributes can contain authentication/CSRF/OIDC state: restrict DB access, encrypt storage, never log attribute bytes or export them as business data. Logical restore drills exclude session and attribute data and assert both tables are empty after restore. Full production/PITR restores must invalidate sessions before opening traffic to avoid restoring previously revoked credentials. Backchannel IdP revocation, administrator revoke-all and production multi-node failover remain unverified.

Private contact policy (Phase 2 increment): self/HR may read home-address/emergency-contact fields; team managers receive null values. Managers cannot submit or decide private team changes. Request lists and pending totals exclude private team requests and audit reads exclude their entries; private free-text reasons therefore remain within the same restricted workflow. This is enforced by application policy, not hidden controls. Direct API and PostgreSQL tests cover profile, directory, legacy employee/request list, inbox/count, history and decision paths. Multiple contacts, full privacy administration and retention ownership remain open.

Hiring access: all hiring lists, submissions, decisions and audits are backend HR-only and tenant-scoped. Independent review checks employee identity in addition to provider subject, preventing alternate-login self approval. Pending request snapshots and audits contain HR-controlled employee inputs and must use their own retention/hold rules. No employee receives a credential or membership automatically. Olivia is a local synthetic reviewer; production IdP role provisioning/MFA remains a release dependency. Acceptance-only rate budgets are bounded and guarded against prod; default rate/body/forwarded-IP behavior is tested deterministically.
