# Requirement traceability

Authoritative source inventory: 45 (HR 6, TS 4, DS 2, PY 9, TM 7, ERP 7, SH 10). Behaviour-level acceptance and separate implementation/verification status: [traceability.json](traceability.json). Derived criteria are marked. Screens/APIs beyond Phase 1 are proposed contracts, not existing endpoints.

| ID | Workflow / roles | Phase | Screen / API resource | Model | Frontend | Backend | Verification |
|---|---|---|---|---|---|---|---|
| HR-01 | Employee records / HR, employee | 1 | `/ (People / profile / change dialog)` / `/api/v1/employees, /api/v1/requests, /api/v1/requests/{id}/decision, /api/v1/employees/{id}/history, /api/v1/directory, /api/v1/employees/{id}, /api/v1/people-summary` | Employee, EmploymentVersion | partial: Phase 1 scope implemented | partial: Phase 1 scope implemented | verified subset; remaining criteria open |
| HR-02 | Organisation structure / HR, manager | 2 | `/ (People / Organisation view / department create dialog)` / `/api/v1/organisation/departments, /api/v1/organisation/history` | Department, Position, ReportingLine | partial: department creation/reference browse and existing scoped employee preview | partial: tenant departments/parent references/effective start and transfer validation | verified department subset; chart/position/hierarchy editing open |
| HR-03 | Employee self-service / employee, approver | 1 | `/ (People / profile / change dialog)` / `/api/v1/employees, /api/v1/requests, /api/v1/requests/{id}/decision, /api/v1/employees/{id}/history` | ChangeRequest | partial: Phase 1 scope implemented | partial: Phase 1 scope implemented | verified subset; remaining criteria open |
| HR-04 | Manager self-service / manager, approver | 1 | `/ (People / profile / change dialog)` / `/api/v1/employees, /api/v1/requests, /api/v1/requests/{id}/decision, /api/v1/employees/{id}/history` | ChangeRequest, EmploymentVersion | partial: Phase 1 scope implemented | partial: Phase 1 scope implemented | verified subset; remaining criteria open |
| HR-05 | Employment lifecycle / HR, IT, payroll | 2 | `/ (People / Hiring / submit and review dialogs)` / `/api/v1/hiring/requests, /api/v1/hiring/requests/{id}/decision, /api/v1/hiring/requests/{id}/history` | Employment, LifecycleRequest | partial: submit/review/approve/reject hire and open assigned employee | partial: approved hire atomically creates employee/audit/pending handoff | verified hiring subset; remaining lifecycle/dependent consumers open |
| HR-06 | Benefits / employee, HR, payroll | 2 | `/benefits` / `/api/v1/benefits` | Plan, Dependant, Enrolment | not implemented | not implemented | not run |
| TS-01 | Attendance / employee, manager | 3 | `/attendance` / `/api/v1/attendance` | Punch, Correction | not implemented | not implemented | not run |
| TS-02 | Timesheets / employee, manager | 3 | `/timesheets` / `/api/v1/timesheets` | Timesheet, TimeEntry | not implemented | not implemented | not run |
| TS-03 | Leave / employee, manager | 3 | `/leave` / `/api/v1/leave` | LeaveRequest, Balance, Calendar | not implemented | not implemented | not run |
| TS-04 | Overtime / employee, manager | 3 | `/overtime` / `/api/v1/overtime` | OvertimeRequest | not implemented | not implemented | not run |
| DS-01 | Documents / employee, HR | 2 | `/documents` / `/api/v1/documents` | Document, Version, RetentionPolicy | not implemented | not implemented | not run |
| DS-02 | HR support / employee, caseworker | 3 | `/support` / `/api/v1/support` | Case, Correspondence, SLA | not implemented | not implemented | not run |
| PY-01 | Payroll setup / payroll admin | 4 | `/payroll/setup` / `/api/v1/payroll/setup` | PayComponent, PayrollGroup, Calendar | not implemented | not implemented | not run |
| PY-02 | Payroll inputs / payroll operator | 4 | `/payroll/inputs` / `/api/v1/payroll/inputs` | PayrollInput, Snapshot | not implemented | not implemented | not run |
| PY-03 | Payroll calculation / payroll operator | 4 | `/payroll/runs` / `/api/v1/payroll/runs` | PayrollRun, RuleVersion, Result | not implemented | not implemented | not run |
| PY-04 | Exceptions / payroll operator | 4 | `/payroll/exceptions` / `/api/v1/payroll/exceptions` | Exception, CorrectionTask | not implemented | not implemented | not run |
| PY-05 | Retroactive payments / payroll operator | 4 | `/payroll/retro` / `/api/v1/payroll/retro` | RetroAdjustment | not implemented | not implemented | not run |
| PY-06 | Off-cycle payroll / payroll operator, approver | 4 | `/payroll/off-cycle` / `/api/v1/payroll/off-cycle` | OffCycleRun | not implemented | not implemented | not run |
| PY-07 | Payroll release / payroll approver | 4 | `/payroll/release` / `/api/v1/payroll/release` | Release, Approval | not implemented | not implemented | not run |
| PY-08 | Employee outputs / employee, payroll | 4 | `/payslips` / `/api/v1/payslips` | Payslip, AnnualStatement | not implemented | not implemented | not run |
| PY-09 | Payment and accounting / treasury, accountant | 4 | `/payroll/reconciliation` / `/api/v1/payroll/reconciliation` | PaymentInstruction, Journal, Reconciliation | not implemented | not implemented | not run |
| TM-01 | Recruitment / recruiter, manager | 5 | `/recruitment` / `/api/v1/recruitment` | Requisition, Candidate, Interview, Offer | not implemented | not implemented | not run |
| TM-02 | Onboarding / new hire, HR | 5 | `/onboarding` / `/api/v1/onboarding` | JoiningTask, Dependency | not implemented | not implemented | not run |
| TM-03 | Performance / employee, manager | 5 | `/performance` / `/api/v1/performance` | Goal, ReviewCycle, Feedback | not implemented | not implemented | not run |
| TM-04 | Learning / employee, learning admin | 5 | `/learning` / `/api/v1/learning` | Course, Assignment, Completion | not implemented | not implemented | not run |
| TM-05 | Compensation planning / manager, compensation admin | 5 | `/compensation` / `/api/v1/compensation` | Budget, Proposal | not implemented | not implemented | not run |
| TM-06 | Succession / talent admin | 5 | `/succession` / `/api/v1/succession` | TalentProfile, SuccessionPlan | not implemented | not implemented | not run |
| TM-07 | Workforce planning / workforce planner | 5 | `/workforce` / `/api/v1/workforce` | Scenario, WorkforceCost | not implemented | not implemented | not run |
| ERP-01 | Finance / accountant, controller | 6 | `/finance` / `/api/v1/finance` | Journal, Ledger, Period | not implemented | not implemented | not run |
| ERP-02 | Procurement / buyer, receiver, approver | 6 | `/procurement` / `/api/v1/procurement` | Requisition, PO, Receipt, Invoice | not implemented | not implemented | not run |
| ERP-03 | Inventory / warehouse operator | 6 | `/inventory` / `/api/v1/inventory` | StockMovement, Valuation | not implemented | not implemented | not run |
| ERP-04 | Sales / sales, credit controller | 6 | `/sales` / `/api/v1/sales` | Quote, Order, Delivery, Invoice, Return | not implemented | not implemented | not run |
| ERP-05 | Manufacturing / production planner | 6 | `/manufacturing` / `/api/v1/manufacturing` | ProductionOrder, BOM, Capacity | not implemented | not implemented | not run |
| ERP-06 | Assets / asset manager | 6 | `/assets` / `/api/v1/assets` | Asset, Depreciation, Disposal | not implemented | not implemented | not run |
| ERP-07 | Projects / project manager | 6 | `/projects` / `/api/v1/projects` | Project, Budget, Expense | not implemented | not implemented | not run |
| SH-01 | Sign-in and access / all, security admin | 1 | `/ (Sign-in)` / `/api/v1/session, /api/csrf, /oauth2/authorization/keycloak, /api/logout` | Identity, Membership, Policy | partial: Phase 1 scope implemented | partial: Phase 1 scope implemented | verified subset; remaining criteria open |
| SH-02 | Approval inbox / requester, approver | 1 | `/ (Approvals)` / `/api/v1/employees, /api/v1/requests, /api/v1/requests/{id}/decision, /api/v1/employees/{id}/history, /api/v1/request-inbox` | ApprovalTask, Decision, Delegation | partial: Phase 1 scope implemented | partial: Phase 1 scope implemented | verified subset; remaining criteria open |
| SH-03 | Notifications / all | 1 | `/ (Notifications dialog)` / `/api/v1/notifications` | Notification, DeliveryAttempt | partial: Phase 1 scope implemented | partial: Phase 1 scope implemented | verified subset; remaining criteria open |
| SH-04 | Reporting / authorised analyst | 7 | `/reports` / `/api/v1/reports` | Projection, ExportJob | not implemented | not implemented | not run |
| SH-05 | Audit history / authorised auditor | 1 | `/ (People / profile / change dialog)` / `/api/v1/employees, /api/v1/requests, /api/v1/requests/{id}/decision, /api/v1/employees/{id}/history` | AuditEvent | partial: Phase 1 scope implemented | partial: Phase 1 scope implemented | verified subset; remaining criteria open |
| SH-06 | Configuration / configuration admin | 2 | `/settings` / `/api/v1/settings` | Calendar, Rule, Template, Reference | partial: department reference creation and browse | partial: validated effective department creation; other configuration planned | verified department subset; remaining configuration open |
| SH-07 | Data migration / migration admin | 7 | `/migration` / `/api/v1/migration` | ImportJob, Mapping, Reconciliation | not implemented | not implemented | not run |
| SH-08 | Integration monitoring / integration operator | 7 | `/integrations` / `/api/v1/integrations` | OutboxEvent, Delivery, Replay | not implemented | not implemented | not run |
| SH-09 | Localisation / all, configuration admin | 2 | `/settings/localisation` / `/api/v1/settings/localisation` | Locale, LegalEntity, Currency | not implemented | not implemented | not run |
| SH-10 | Privacy administration / employee, privacy officer | 7 | `/privacy` / `/api/v1/privacy` | PrivacyRequest, Retention, LegalHold | not implemented | not implemented | not run |
