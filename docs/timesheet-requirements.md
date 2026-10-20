# Detailed timesheet acceptance baseline

Authoritative user-supplied detailed timesheet baseline FE-01–FE-28 / BE-01–BE-32, received 2026-10-21. All **60 IDs** are retained alongside the original 45 ERP IDs. No full source requirement is signed off here.

## Assumptions and boundaries

- Independent application; no SAP Fieldglass or Workday connection is implied.
- Workday-oriented employee/manager/payroll flows and shared controls are phase 3/4. Fieldglass-oriented buyer/supplier/billing flows require internal identity, assignment and billing capabilities plus phase 6 dependencies.
- Weekly Monday–Sunday local duration template is existing behaviour, not a claim of configurable templates.
- Activity codes are free text labels, never evidence of an assignment catalogue.
- No statutory overtime, break, holiday, payroll or billing rules are invented.
- Copying reuses existing time zone and labelled minutes; future/pre-employment time and target policy are revalidated. Schedule autofill is incomplete.
- Reports are bounded to 1000 sheets and 53 reporting weeks; all filtered results are exported, not selected page only. Reports contain minutes, not monetary costs.

## Requirement mapping

### FE-01 — All roles

Screen/API: Session / time navigation. Core: Identity / scope. Phases: 3. Frontend: partial. Backend: partial.

- **FE-01.1** Sign in using configured authentication — not individually signed off.
- **FE-01.2** Display only role-permitted actions — not individually signed off.
- **FE-01.3** Reject prohibited server requests — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-01.1, FE-01.2, FE-01.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-02 — Worker / manager

Screen/API: My week / GET dashboard?week={Monday}. Core: TimeService.dashboard / current Sheet / Period / current review scope. Phases: 3. Frontend: partial. Backend: partial.

- **FE-02.1** Display status and deadline — partial: persisted status verified; configured policy displayed; no deadline reminder job.
- **FE-02.2** Calculate missing time from expected schedule — not individually signed off.
- **FE-02.3** Show pending approvals and alerts — not individually signed off.

Acceptance checks: TimekeepingWorkflowTest.dashboardUsesCurrentEpisodeAndCurrentTeamScopeWithoutInferringSchedule; tests/timesheet-report-copy.spec.ts — weekly dashboard.

Internal engineering work; not blocked by unavailable provider credentials.

### FE-03 — Worker / HR

Screen/API: Reporting periods / GET periods/{week}. Core: Period revisions. Phases: 3. Frontend: partial. Backend: partial.

- **FE-03.1** Navigate configured weeks — not individually signed off.
- **FE-03.2** Display open or closed status and deadline — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-03.1, FE-03.2; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-04 — Worker

Screen/API: Sheet editor / POST sheets. Core: Sheet / Entry / Template. Phases: 3. Frontend: partial. Backend: partial.

- **FE-04.1** Enter daily duration — not individually signed off.
- **FE-04.2** Enter start/end intervals — not individually signed off.
- **FE-04.3** Apply assigned template — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-04.1, FE-04.2, FE-04.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-05 — Worker

Screen/API: Assignment selector / planned assignments API. Core: Effective assignment. Phases: 3, 6. Frontend: missing. Backend: missing.

- **FE-05.1** Select eligible job and position — not individually signed off.
- **FE-05.2** Select project task and worktag — not individually signed off.
- **FE-05.3** Select cost centre and work order — not individually signed off.
- **FE-05.4** Display applicable assignment dates — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-05.1, FE-05.2, FE-05.3, FE-05.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-06 — Worker / HR

Screen/API: Category selector / planned categories API. Core: Time category. Phases: 3. Frontend: missing. Backend: missing.

- **FE-06.1** Select regular and overtime — not individually signed off.
- **FE-06.2** Select unpaid break — not individually signed off.
- **FE-06.3** Select on-call and holiday work — not individually signed off.
- **FE-06.4** Only configured eligible categories are available — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-06.1, FE-06.2, FE-06.3, FE-06.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-07 — Worker

Screen/API: Attendance / POST punches/{action}. Core: Shift / Time event. Phases: 3. Frontend: partial. Backend: partial.

- **FE-07.1** Clock in — not individually signed off.
- **FE-07.2** Clock out — not individually signed off.
- **FE-07.3** Record breaks when enabled — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-07.1, FE-07.2, FE-07.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-08 — Worker

Screen/API: My timesheets / POST sheets. Core: Sheet / Entry. Phases: 3. Frontend: partial. Backend: partial.

- **FE-08.1** Save an incomplete draft — not individually signed off.
- **FE-08.2** Reload persisted draft — not individually signed off.
- **FE-08.3** Resume permitted edits — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-08.1, FE-08.2, FE-08.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-09 — Worker

Screen/API: Copy details / POST sheets/copy. Core: Sheet / copy provenance. Phases: 3. Frontend: partial. Backend: partial.

- **FE-09.1** Copy eligible prior-period entries without overwriting — not individually signed off.
- **FE-09.2** Revalidate target period and employment — verified implemented subset; see exact limits.
- **FE-09.3** Autofill from effective work schedules — not individually signed off.

Acceptance checks: TimekeepingWorkflowTest.copiesAreOwnedVersionedIdempotentAndNeverOverwriteOrPost; TimekeepingWorkflowTest.concurrentCopyRetriesCreateExactlyOneDraftAndReceipt; tests/timesheet-report-copy.spec.ts — copy/reload/submit/approve.

Internal engineering work; not blocked by unavailable provider credentials.

### FE-10 — Worker / approver / cost viewer

Screen/API: Details / GET sheets/{id}. Core: Sheet / Allocation / Rate snapshot. Phases: 3. Frontend: partial. Backend: partial.

- **FE-10.1** Display daily and period minutes — not individually signed off.
- **FE-10.2** Display configured overtime — not individually signed off.
- **FE-10.3** Display billable hours — not individually signed off.
- **FE-10.4** Restrict monetary costs to authorised roles — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-10.1, FE-10.2, FE-10.3, FE-10.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-11 — Worker / reviewer

Screen/API: Editor and evidence. Core: Validation / Exception. Phases: 3. Frontend: partial. Backend: partial.

- **FE-11.1** Highlight missing fields and invalid durations — not individually signed off.
- **FE-11.2** Detect overlaps and missed punches — not individually signed off.
- **FE-11.3** Detect invalid assignments — not individually signed off.
- **FE-11.4** Display unusual-hours and policy warning/block rules — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-11.1, FE-11.2, FE-11.3, FE-11.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-12 — Worker / reviewer

Screen/API: Details / planned comments and attachments API. Core: Comment / private document link. Phases: 3. Frontend: partial. Backend: partial.

- **FE-12.1** Persist explanations — not individually signed off.
- **FE-12.2** Attach validated private supporting documents — not individually signed off.
- **FE-12.3** Authorise attachment retrieval — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-12.1, FE-12.2, FE-12.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-13 — Worker

Screen/API: Details / POST sheets/{id}/actions. Core: Sheet / Declaration. Phases: 3. Frontend: partial. Backend: partial.

- **FE-13.1** Review totals before submitting — verified implemented subset; see exact limits.
- **FE-13.2** Persist required declaration version and acknowledgement — not individually signed off.
- **FE-13.3** Submit through valid transition — verified implemented subset; see exact limits.

Acceptance checks: TimekeepingWorkflowTest (actual PostgreSQL workflows, auth, immutable audit and transitions); tests/timesheet-management.spec.ts (prior BM evidence); tests/timesheet-report-copy.spec.ts (BN verified subset).

Internal engineering work; not blocked by unavailable provider credentials.

### FE-14 — Worker / approver

Screen/API: Details and Time history. Core: Sheet / Audit / Approval route. Phases: 3. Frontend: partial. Backend: partial.

- **FE-14.1** Display status — not individually signed off.
- **FE-14.2** Display pending named approvers — not individually signed off.
- **FE-14.3** Display full history and reasons — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-14.1, FE-14.2, FE-14.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-15 — Worker / HR

Screen/API: Details and editor. Core: Sheet / Adjustment. Phases: 3. Frontend: partial. Backend: partial.

- **FE-15.1** Recall pending submission under policy — verified implemented subset; see exact limits.
- **FE-15.2** Correct returned records and resubmit — verified implemented subset; see exact limits.
- **FE-15.3** Request governed post-processing adjustments — not individually signed off.

Acceptance checks: TimekeepingWorkflowTest (actual PostgreSQL workflows, auth, immutable audit and transitions); tests/timesheet-management.spec.ts (prior BM evidence); tests/timesheet-report-copy.spec.ts (BN verified subset).

Internal engineering work; not blocked by unavailable provider credentials.

### FE-16 — Manager / buyer / delegate

Screen/API: Team approvals. Core: Approval task / scoped query. Phases: 3, 6. Frontend: partial. Backend: partial.

- **FE-16.1** Filter by worker and period — not individually signed off.
- **FE-16.2** Filter by organisation and project — not individually signed off.
- **FE-16.3** Filter by supplier and exception — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-16.1, FE-16.2, FE-16.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-17 — Approver / delegate

Screen/API: Team approvals / selected-actions. Core: Sheet / immutable batch receipt. Phases: 3. Frontend: partial. Backend: partial.

- **FE-17.1** Approve return or reject with comments — not individually signed off.
- **FE-17.2** Scope individual decisions — not individually signed off.
- **FE-17.3** Scope bulk decisions to explicit selected records — not individually signed off.
- **FE-17.4** Apply configured authority and approval order — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-17.1, FE-17.2, FE-17.3, FE-17.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-18 — Manager / buyer

Screen/API: Team overview. Core: Expected worker / Sheet / Exception. Phases: 3. Frontend: partial. Backend: partial.

- **FE-18.1** Show team submissions — not individually signed off.
- **FE-18.2** Identify missing expected timesheets — not individually signed off.
- **FE-18.3** Show overtime pending approvals and exceptions — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-18.1, FE-18.2, FE-18.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-19 — Worker / approver

Screen/API: Notifications. Core: Notification / delivery attempt. Phases: 3. Frontend: missing. Backend: missing.

- **FE-19.1** Show reminders — not individually signed off.
- **FE-19.2** Show approval requests and returns — not individually signed off.
- **FE-19.3** Show downstream failures — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-19.1, FE-19.2, FE-19.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-20 — Worker / manager / HR

Screen/API: Time reports / GET reports / POST reports/export. Core: ReportRow / Allocation. Phases: 3. Frontend: partial. Backend: partial.

- **FE-20.1** Filter authorised time and approval status — verified implemented subset; see exact limits.
- **FE-20.2** Export all matching bounded results — verified implemented subset; see exact limits.
- **FE-20.3** Report exceptions — not individually signed off.
- **FE-20.4** Report authorised labour costs — not individually signed off.

Acceptance checks: TimekeepingWorkflowTest.reportsAndExportsAreScopedBoundedAndReconcileToRealAllocations; TimeReportLimitTest; TimeReportCsvTest; tests/timesheet-report-copy.spec.ts — filtered report/CSV/access/CSRF.

Internal engineering work; not blocked by unavailable provider credentials.

### FE-21 — HR / time administrator

Screen/API: Reporting periods and planned administration. Core: Effective configuration. Phases: 3. Frontend: partial. Backend: partial.

- **FE-21.1** Manage templates and calendars — not individually signed off.
- **FE-21.2** Manage categories policies and rates — not individually signed off.
- **FE-21.3** Manage approval routing and delegation — not individually signed off.
- **FE-21.4** Manage access assignments — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-21.1, FE-21.2, FE-21.3, FE-21.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-22 — Worker / approver

Screen/API: Responsive time workspace. Core: Shared workflows. Phases: 3. Frontend: partial. Backend: partial.

- **FE-22.1** Operate time entry on supported mobile sizes — not individually signed off.
- **FE-22.2** Submit on mobile — not individually signed off.
- **FE-22.3** Review and approve on mobile with keyboard and assistive technology — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-22.1, FE-22.2, FE-22.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-23 — Worker / buyer / supplier

Screen/API: Planned supplier workspace. Core: Buyer / Supplier / access grant. Phases: 3, 6. Frontend: partial. Backend: partial.

- **FE-23.1** Provide worker views — not individually signed off.
- **FE-23.2** Scope buyer views — not individually signed off.
- **FE-23.3** Scope supplier representative views — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-23.1, FE-23.2, FE-23.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-24 — Authorised importer

Screen/API: Planned time import screen. Core: Import batch / row results. Phases: 3. Frontend: missing. Backend: missing.

- **FE-24.1** Upload bounded time data — not individually signed off.
- **FE-24.2** Display each row outcome — not individually signed off.
- **FE-24.3** Give correction instructions and replay without duplicates — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-24.1, FE-24.2, FE-24.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-25 — Worker / reviewer

Screen/API: Planned schedule context. Core: Schedule / Holiday / Absence. Phases: 3. Frontend: missing. Backend: missing.

- **FE-25.1** Display scheduled hours — not individually signed off.
- **FE-25.2** Display holidays — not individually signed off.
- **FE-25.3** Display relevant absence beside entries — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-25.1, FE-25.2, FE-25.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### FE-26 — Buyer / supplier

Screen/API: Planned billing status. Core: Billing handoff / adjustment. Phases: 3, 4, 6, 7. Frontend: missing. Backend: missing.

- **FE-26.1** Display ready for invoicing — not individually signed off.
- **FE-26.2** Display invoiced time — not individually signed off.
- **FE-26.3** Display awaiting-correction state — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-26.1, FE-26.2, FE-26.3; existing subset evidence remains BM, without full source sign-off..

Internal implementation remains authorised. Live provider connection additionally needs verified contracts, credentials and identifier ownership.

### FE-27 — Payroll operator / worker

Screen/API: Planned payroll status. Core: Payroll handoff / acknowledgement. Phases: 3, 4, 6, 7. Frontend: missing. Backend: missing.

- **FE-27.1** Display readiness — not individually signed off.
- **FE-27.2** Display processing outcome — not individually signed off.
- **FE-27.3** Display outstanding issues — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-27.1, FE-27.2, FE-27.3; existing subset evidence remains BM, without full source sign-off..

Internal implementation remains authorised. Live provider connection additionally needs verified contracts, credentials and identifier ownership.

### FE-28 — Integration operator

Screen/API: Planned reconciliation screen. Core: Transfer / attempt / difference. Phases: 3, 4, 6, 7. Frontend: missing. Backend: missing.

- **FE-28.1** Display successful transfers — not individually signed off.
- **FE-28.2** Display rejected records and retries — not individually signed off.
- **FE-28.3** Display unresolved differences — not individually signed off.

Acceptance checks: Planned behaviour checks: FE-28.1, FE-28.2, FE-28.3; existing subset evidence remains BM, without full source sign-off..

Internal implementation remains authorised. Live provider connection additionally needs verified contracts, credentials and identifier ownership.

### BE-01 — All roles

Screen/API: Session and all time endpoints. Core: Identity / organisational scope. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-01.1** Authenticate requests — not individually signed off.
- **BE-01.2** Enforce read edit submit and approve grants — not individually signed off.
- **BE-01.3** Enforce import and export grants — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-01.1, BE-01.2, BE-01.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-02 — HR / integration operator

Screen/API: Published corehr TimeWorkforceAccess. Core: Worker / employment episode / assignment. Phases: 3, 6. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-02.1** Maintain workers managers and employment dates — not individually signed off.
- **BE-02.2** Maintain jobs and assignments — not individually signed off.
- **BE-02.3** Maintain suppliers and organisational boundaries — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-02.1, BE-02.2, BE-02.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-03 — Worker / importer

Screen/API: Save copy and planned assignment eligibility. Core: Employment episode / effective rule. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-03.1** Validate worker episode and dates — not individually signed off.
- **BE-03.2** Resolve effective assignments — not individually signed off.
- **BE-03.3** Resolve permitted time-entry system — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-03.1, BE-03.2, BE-03.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-04 — HR / worker

Screen/API: GET periods/{week} / POST periods. Core: Immutable Period revisions. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-04.1** Persist reporting weeks and deadlines — not individually signed off.
- **BE-04.2** Enforce open and closed state — not individually signed off.
- **BE-04.3** Apply configured calendars — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-04.1, BE-04.2, BE-04.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-05 — Worker / reviewer

Screen/API: Sheet and history APIs. Core: Sheet / Entry / Audit. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-05.1** Store headers daily entries totals and versions — not individually signed off.
- **BE-05.2** Store comments — not individually signed off.
- **BE-05.3** Store attachments — not individually signed off.
- **BE-05.4** Store declarations — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-05.1, BE-05.2, BE-05.3, BE-05.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-06 — Worker / importer

Screen/API: Save / correction validation. Core: TimePolicy / assignment rules. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-06.1** Validate required fields and date ranges — not individually signed off.
- **BE-06.2** Detect overlaps and duplicate daily entries — not individually signed off.
- **BE-06.3** Validate assignment eligibility — not individually signed off.
- **BE-06.4** Enforce configured hour limits — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-06.1, BE-06.2, BE-06.3, BE-06.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-07 — Worker / payroll / billing

Screen/API: Time calculation use case. Core: Minutes / calculation snapshot. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-07.1** Calculate elapsed worked minutes — not individually signed off.
- **BE-07.2** Deduct unpaid breaks — not individually signed off.
- **BE-07.3** Apply configured rounding — not individually signed off.
- **BE-07.4** Calculate overtime and premiums — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-07.1, BE-07.2, BE-07.3, BE-07.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-08 — Worker / reviewer

Screen/API: Planned schedule validation. Core: Schedule / holiday / absence contracts. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-08.1** Apply work schedules — not individually signed off.
- **BE-08.2** Apply holidays — not individually signed off.
- **BE-08.3** Detect absence conflicts — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-08.1, BE-08.2, BE-08.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-09 — Worker / approver

Screen/API: POST sheets/{id}/actions. Core: Sheet state machine. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-09.1** Enforce draft submitted approved returned rejected and corrected states — verified implemented subset; see exact limits.
- **BE-09.2** Require correction before resubmission — verified implemented subset; see exact limits.
- **BE-09.3** Track independent payroll and billing processing states — not individually signed off.

Acceptance checks: TimekeepingWorkflowTest (actual PostgreSQL workflows, auth, immutable audit and transitions); tests/timesheet-management.spec.ts (prior BM evidence); tests/timesheet-report-copy.spec.ts (BN verified subset).

Internal engineering work; not blocked by unavailable provider credentials.

### BE-10 — Manager / buyer / delegate

Screen/API: Review queue / planned route resolution. Core: Approval route / stage / task. Phases: 3, 6. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-10.1** Route to current manager — not individually signed off.
- **BE-10.2** Route project buyer or group approvals — not individually signed off.
- **BE-10.3** Support separate job/project routes and ordered stages — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-10.1, BE-10.2, BE-10.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-11 — Approver / delegate / HR

Screen/API: Planned delegation and reminder jobs. Core: Delegation / reminder / escalation. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-11.1** Authorise bounded substitute approvers — not individually signed off.
- **BE-11.2** Remind overdue tasks — not individually signed off.
- **BE-11.3** Escalate according to configured rules — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-11.1, BE-11.2, BE-11.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-12 — Worker / HR

Screen/API: Save / transition / period controls. Core: Sheet / Period / downstream lock. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-12.1** Lock submitted and approved edits — not individually signed off.
- **BE-12.2** Enforce period closure — not individually signed off.
- **BE-12.3** Lock processed payroll and invoiced time — not individually signed off.
- **BE-12.4** Permit only governed correction flows — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-12.1, BE-12.2, BE-12.3, BE-12.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-13 — Worker / HR / payroll / billing

Screen/API: Planned adjustment requests. Core: Original Sheet / Adjustment / allocation. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-13.1** Preserve original approved records — not individually signed off.
- **BE-13.2** Authorise corrections and recalculate — not individually signed off.
- **BE-13.3** Reapprove adjustments — not individually signed off.
- **BE-13.4** Create downstream delta records — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-13.1, BE-13.2, BE-13.3, BE-13.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-14 — Worker / approver

Screen/API: Planned time notification processor. Core: Business event / outbox / delivery. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-14.1** Emit committed workflow notifications — not individually signed off.
- **BE-14.2** Generate deadline reminders — not individually signed off.
- **BE-14.3** Persist delivery attempts and outcomes — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-14.1, BE-14.2, BE-14.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-15 — Worker / authorised reviewer

Screen/API: GET history/{employeeId}. Core: Append-only Audit. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-15.1** Record actor instant action and reason — verified implemented subset; see exact limits.
- **BE-15.2** Record old and new values — verified implemented subset; see exact limits.
- **BE-15.3** Retain correlation and copy provenance — verified implemented subset; see exact limits.
- **BE-15.4** Restrict audit access — verified implemented subset; see exact limits.

Acceptance checks: TimekeepingWorkflowTest (actual PostgreSQL workflows, auth, immutable audit and transitions); tests/timesheet-management.spec.ts (prior BM evidence); tests/timesheet-report-copy.spec.ts (BN verified subset).

Internal engineering work; not blocked by unavailable provider credentials.

### BE-16 — Importer / integration operator

Screen/API: Planned imports API. Core: ImportBatch / ImportRow. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-16.1** Accept spreadsheet clock supplier and external records — not individually signed off.
- **BE-16.2** Validate bounded batches — not individually signed off.
- **BE-16.3** Return row errors — not individually signed off.
- **BE-16.4** Allow safe correction and replay — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-16.1, BE-16.2, BE-16.3, BE-16.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-17 — Integration operator

Screen/API: Planned outbound ports / transfers. Core: Mapping / transfer / acknowledgement. Phases: 3, 4, 6, 7. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-17.1** Exchange worker and assignment data — not individually signed off.
- **BE-17.2** Exchange time and approval data — not individually signed off.
- **BE-17.3** Exchange payroll and billing data via supported interfaces — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-17.1, BE-17.2, BE-17.3; existing subset evidence remains BM, without full source sign-off..

Internal implementation remains authorised. Live provider connection additionally needs verified contracts, credentials and identifier ownership.

### BE-18 — Worker / approver / operator

Screen/API: Command receipts and planned transfer deduplication. Core: Command / unique Sheet / transfer key. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-18.1** Prevent duplicate entries and submissions — not individually signed off.
- **BE-18.2** Prevent duplicate imports — not individually signed off.
- **BE-18.3** Prevent duplicate payroll and billing handoffs — not individually signed off.
- **BE-18.4** Return stable outcomes on safe retry — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-18.1, BE-18.2, BE-18.3, BE-18.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-19 — Integration operator

Screen/API: Planned reconciliation API. Core: Transfer / acknowledgement / difference. Phases: 3, 4, 6, 7. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-19.1** Track exports and acknowledgements — not individually signed off.
- **BE-19.2** Track rejected records and failures — not individually signed off.
- **BE-19.3** Support bounded authorised retries — not individually signed off.
- **BE-19.4** Reconcile unresolved differences — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-19.1, BE-19.2, BE-19.3, BE-19.4; existing subset evidence remains BM, without full source sign-off..

Internal implementation remains authorised. Live provider connection additionally needs verified contracts, credentials and identifier ownership.

### BE-20 — Worker / manager / HR

Screen/API: GET reports / POST reports/export. Core: ReportRow / Allocation. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-20.1** Summarise scoped hours and approval states — verified implemented subset; see exact limits.
- **BE-20.2** Summarise exceptions — not individually signed off.
- **BE-20.3** Summarise authorised labour costs — not individually signed off.
- **BE-20.4** Summarise downstream processing — not individually signed off.

Acceptance checks: TimekeepingWorkflowTest.reportsAndExportsAreScopedBoundedAndReconcileToRealAllocations; TimeReportLimitTest; tests/timesheet-report-copy.spec.ts.

Internal engineering work; not blocked by unavailable provider credentials.

### BE-21 — HR / time administrator

Screen/API: Reporting periods and planned effective config API. Core: Policy / Template / Calendar / Rate / Mapping. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-21.1** Version effective policies templates and calendars — not individually signed off.
- **BE-21.2** Version rate rules and mappings — not individually signed off.
- **BE-21.3** Version approval rules — not individually signed off.
- **BE-21.4** Preserve historical configuration snapshots — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-21.1, BE-21.2, BE-21.3, BE-21.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-22 — Worker / reviewer / HR

Screen/API: Save copy decide configurePeriod. Core: Optimistic revision / employee lock. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: implemented-subset.

- **BE-22.1** Reject stale edits — verified implemented subset; see exact limits.
- **BE-22.2** Reject stale copied sources — verified implemented subset; see exact limits.
- **BE-22.3** Resolve concurrent approvals safely — verified implemented subset; see exact limits.
- **BE-22.4** Prevent silent overwrite — verified implemented subset; see exact limits.

Acceptance checks: TimekeepingWorkflowTest; TimeIdentityRaceTest; tests/timesheet-report-copy.spec.ts.

Internal engineering work; not blocked by unavailable provider credentials.

### BE-23 — All roles

Screen/API: Every scoped query and command. Core: Tenant / organisation / supplier grant. Phases: 3, 6. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-23.1** Isolate tenants and current employee/team records — not individually signed off.
- **BE-23.2** Isolate buyer organisations — not individually signed off.
- **BE-23.3** Isolate suppliers — not individually signed off.
- **BE-23.4** Restrict administrative access — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-23.1, BE-23.2, BE-23.3, BE-23.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-24 — Payroll / billing / cost viewer

Screen/API: Planned valuation use case. Core: Rate rule / currency / historical amount. Phases: 3, 4, 6, 7. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-24.1** Apply configured payable rates — not individually signed off.
- **BE-24.2** Apply billable rates — not individually signed off.
- **BE-24.3** Preserve historical rate currency rounding and values — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-24.1, BE-24.2, BE-24.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-25 — Buyer / billing operator

Screen/API: Planned billing handoff. Core: Approved allocation / BillingTransfer. Phases: 3, 4, 6, 7. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-25.1** Handoff eligible approved time — not individually signed off.
- **BE-25.2** Track invoice acknowledgement — not individually signed off.
- **BE-25.3** Process auditable billing adjustments — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-25.1, BE-25.2, BE-25.3; existing subset evidence remains BM, without full source sign-off..

Internal implementation remains authorised. Live provider connection additionally needs verified contracts, credentials and identifier ownership.

### BE-26 — Payroll operator

Screen/API: Planned payroll handoff. Core: Approved allocation / PayrollTransfer. Phases: 3, 4, 6, 7. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-26.1** Handoff eligible calculated time — not individually signed off.
- **BE-26.2** Track results — not individually signed off.
- **BE-26.3** Process corrections without duplicates — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-26.1, BE-26.2, BE-26.3; existing subset evidence remains BM, without full source sign-off..

Internal implementation remains authorised. Live provider connection additionally needs verified contracts, credentials and identifier ownership.

### BE-27 — Worker / clock importer

Screen/API: Attendance APIs and planned conversion. Core: Shift / Event / calculated Entry. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-27.1** Process in and out events — not individually signed off.
- **BE-27.2** Process break events — not individually signed off.
- **BE-27.3** Convert complete event sequences into calculated time entries — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-27.1, BE-27.2, BE-27.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-28 — Worker / reviewer

Screen/API: Time validation and planned exception rules. Core: ExceptionRule / Exception. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: partial.

- **BE-28.1** Detect missing punches — not individually signed off.
- **BE-28.2** Detect unusual hours — not individually signed off.
- **BE-28.3** Configure warning versus blocking severity — not individually signed off.
- **BE-28.4** Resolve and audit exceptions — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-28.1, BE-28.2, BE-28.3, BE-28.4; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-29 — Integration administrator

Screen/API: Planned mapping administration. Core: ExternalMapping / effective mapping version. Phases: 3, 4, 6, 7. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-29.1** Map worker and assignment IDs — not individually signed off.
- **BE-29.2** Map categories cost centres and projects — not individually signed off.
- **BE-29.3** Map reporting periods — not individually signed off.
- **BE-29.4** Reject ambiguous unmapped records — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-29.1, BE-29.2, BE-29.3, BE-29.4; existing subset evidence remains BM, without full source sign-off..

Internal implementation remains authorised. Live provider connection additionally needs verified contracts, credentials and identifier ownership.

### BE-30 — Integration administrator

Screen/API: Planned ownership administration. Core: SystemOfRecord / inbound policy. Phases: 3, 4, 6, 7. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-30.1** Define worker and assignment ownership — not individually signed off.
- **BE-30.2** Define entered-time and approval ownership — not individually signed off.
- **BE-30.3** Define payroll and billing ownership — not individually signed off.
- **BE-30.4** Reject unauthorised source changes — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-30.1, BE-30.2, BE-30.3, BE-30.4; existing subset evidence remains BM, without full source sign-off..

Internal implementation remains authorised. Live provider connection additionally needs verified contracts, credentials and identifier ownership.

### BE-31 — Approver / importer

Screen/API: Planned transferred-time route resolution. Core: Import source / approval rule / task. Phases: 3. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-31.1** Imported time remains subject to configured approval — not individually signed off.
- **BE-31.2** Transfer never silently bypasses required approval — not individually signed off.
- **BE-31.3** Persist route and decision provenance — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-31.1, BE-31.2, BE-31.3; existing subset evidence remains BM, without full source sign-off..

Internal engineering work; not blocked by unavailable provider credentials.

### BE-32 — Integration operator / HR

Screen/API: Planned correction synchronisation. Core: Adjustment / cancellation / transfer. Phases: 3, 4, 6, 7. Frontend: see linked FE criteria; not independently complete. Backend: missing.

- **BE-32.1** Propagate corrections — not individually signed off.
- **BE-32.2** Propagate cancellations — not individually signed off.
- **BE-32.3** Retain original and replacement identifiers — not individually signed off.
- **BE-32.4** Reconcile retries and acknowledgement history — not individually signed off.

Acceptance checks: Planned behaviour checks: BE-32.1, BE-32.2, BE-32.3, BE-32.4; existing subset evidence remains BM, without full source sign-off..

Internal implementation remains authorised. Live provider connection additionally needs verified contracts, credentials and identifier ownership.

