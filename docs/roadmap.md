# Phases and exit gates
No source ID can be completed by a subset of its behaviours. Frontend/backend and verification are separate in traceability.json. Every phase requires audit, permissions, real persistence, UI error handling and executed tests.

| Phase | Prerequisites | IDs and deliverables | Exit evidence |
|---|---|---|---|
| 0 | repository inspection, all 45 source rows | architecture, design, traceability, threat model, NFRs | inventory assertion; decisions documented |
| 1 | Phase 0, Java/Postgres | HR-01 directory/profile subset, HR-03 personal change, HR-04 team/job change subset; SH-01 identity/access, SH-02 decisions, SH-03 in-app status, SH-05 audit | authenticated UI-to-Postgres change/decision/history, independent reviewer, effective dates, conflict/replay/tenant tests, browser checks; OIDC gate separately tracked |
| 2 | Phase 1 model | complete HR-01–06, DS-01, SH-06 calendars/reference/config, SH-09 localisation | lifecycle, hierarchy, benefits, document scan/retention scenarios |
| 3 | core HR and calendar | TS-01–04, DS-02; SH-02 approvals, SH-03 reminders/escalation | punches, time, leave/cancel/accrual, overtime, support cases |
| 4 | time + effective compensation; country rules for live use | PY-01–09; minimal real ERP-01 ledger, SH-04 payroll reporting, SH-08 adapters | hire-to-pay, leave-to-pay, transfer costing, decimal boundaries, retro/offcycle, segregation, balanced posting/payment reconciliation; demo rules labelled |
| 5 | core HR/payroll contracts | TM-01–07; SH-02/03 talent workflows | recruiting through onboarding, reviews, training expiry, comp budgets, succession and scenario costing |
| 6 | ledger + module contracts | ERP-01–07; SH-06 ERP configuration, SH-08 real adapters | close, procure-to-pay, order-to-cash, stock valuation, production, depreciation, project costing |
| 7 | all workflow evidence | all 45 reconciliation; complete SH-04 reporting, SH-07 migration, SH-08 monitoring/replay, SH-10 privacy | migration rehearsal, restore, rollback, security/load/manual a11y, operational dashboards and accountable release decision |

SH-01/02/03/05 apply in every consuming phase. SH-04 projections start whenever required, SH-07 onboarding migration starts Phase 2, SH-08 integration outbox starts Phase 4, SH-10 retention/hold data model starts Phase 2. None are unowned platform work. Deferred means scheduled scope, never removed. Public deployment, real data migration and live payments require explicit release authorisation.
