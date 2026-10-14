# Non-functional register
Targets are proposed except the explicitly measured scoped checks below. No load benchmark has been executed. Batch jobs and uploads are excluded from interactive latency targets and require separate measurement.

| ID | Target | Workload / measurement | Phase | Status |
|---|---|---|---|---|
| NFR-01 | API p95 <=500ms, p99 <=1.5s | 10k employees, 100 concurrent sessions, 80% directory/profile reads, 15% inbox, 5% writes, 30min after 5min warmup; 2 vCPU app/4GB + 2 vCPU DB/8GB; k6 | 1,7 | proposed |
| NFR-02 | p75 LCP <=2.5s, INP <=200ms, CLS <=0.1 | directory/profile/inbox, mobile 4G + desktop; lab separately from 28-day RUM | 1,7 | proposed |
| NFR-03 | 99.9% monthly availability | ingress success for core authenticated interactive requests; exclude invalid requests, include deploy outages; 43.2min/30d error budget | 7 | proposed |
| NFR-04 | RPO <=15min, RTO <=4h | encrypted PITR/WAL and isolated restore exercise; budget and hosting unresolved | 7 | blocked: production infrastructure; local logical restore measured (see verification.md) |
| NFR-05 | 10k initial /100k growth employees | directory/report/import tests; payroll target after country/rule complexity decision | 2–7 | proposed |
| NFR-06 | WCAG 2.2 AA critical journeys | axe, keyboard, zoom and screen reader; latest two Chrome/Edge/Firefox/Safari and iOS Safari/Android Chrome | all | measured subset — see verification.md; full-module gate open |
| NFR-07 | No forbidden imports or failing critical tests | ArchUnit, javac, ESLint, tsc, PostgreSQL integration, workflow E2E | all | measured subset — see verification.md; full-module gate open |
| NFR-08 | No exploitable high/critical at release without accountable acceptance | dependency, secret, SAST, container scans and ASVS review | all,7 | blocked: dependency findings and outstanding release review |
| NFR-09 | Locale/currency/timezone correctness | explicit UTC instants vs local dates; translation-ready copy; country fields in Phase 2 | 2,4 | proposed |

Observed checks: zero axe violations in the four tested Phase 1 desktop states, keyboard dialog/390px mobile checks, eight browser/API tests and seventeen Java tests. These do not establish full WCAG conformance or workload performance. OIDC/MFA/provider logout passed in a separate sandbox test. Security remains blocked by the open high development-dependency finding and unexecuted production security reviews.

NFR-04 partial evidence: a local synthetic logical restore completed in 4.919 seconds, with 18 approved changes and 92 audit records reconciled by full-row digests and integrity checks. This does not satisfy production RPO/RTO: no PITR/WAL, offsite backup, full application/IdP recovery or production workload measured. Evidence: `evidence/restore-check.json`.
