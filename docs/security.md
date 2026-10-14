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
