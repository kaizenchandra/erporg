# Payroll and accounting acceptance register

All **90** supplied IDs are retained. Each listed behaviour has a derived check; no whole source ID is marked complete. Partial means a verified demonstration subset only, subject to the exact limits and verification checkpoint. The original 45 ERP/HCM IDs and detailed timesheet register are unchanged.

## Delivery order

1. PF1: approved time → demonstration payroll → independent finalization → released own payslip → balanced draft journal → independent finance approval/post → trial balance; manual reversal and fiscal lock controls.
2. PF2: effective compensation/pay groups/calendars, benefits/absence/expense ownership, variable inputs, configurable components/rules and approval policies; then jurisdictional validation.
3. PF3: retro/off-cycle/final settlement, payment authorization/test bank acknowledgements, correction propagation, statutory outputs and reconciliation.
4. PF4: AP/AR/expenses/procurement matching, payments/receipts and bank reconciliation.
5. PF5: assets, allocations/accruals, intercompany/FX/tax, close tasks, statements and consolidation; cross-module release verification.

All phases are committed scope, not optional omissions. Missing internal modules are distinguished from provider credentials.

## PAY-FE-01 — Role-based access

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Explicit payroll prepare/approve entity grants plus employee ownership. Full manager and finance payroll role design remains incomplete.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-FE-01.1** Given an authorised user and configured business context: Display employee payroll actions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-01.2** Given an authorised user and configured business context: Display manager actions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-01.3** Given an authorised user and configured business context: Display payroll-administrator actions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-01.4** Given an authorised user and configured business context: Display finance actions under explicit grants. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-02 — Payroll dashboard

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Persisted paginated payroll register exposes status and blocking exceptions; no pay-calendar dashboard.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-FE-02.1** Given an authorised user and configured business context: Display payroll calendars. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-02.2** Given an authorised user and configured business context: Display processing status. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-02.3** Given an authorised user and configured business context: Display deadlines. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-02.4** Given an authorised user and configured business context: Display employee counts. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-02.5** Given an authorised user and configured business context: Display exceptions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-02.6** Given an authorised user and configured business context: Display pending approvals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-03 — Employee payroll profile

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Result shows current employee/episode, source, entered rate and totals; no bank/tax/pay-group profile.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-FE-03.1** Given an authorised user and configured business context: Display authorised employment. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-03.2** Given an authorised user and configured business context: Display compensation. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-03.3** Given an authorised user and configured business context: Display pay group. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-03.4** Given an authorised user and configured business context: Display tax data. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-03.5** Given an authorised user and configured business context: Display bank and payment information. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-04 — Employee self-service

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Employee self-service workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Employee self-service aggregate, use cases, migrations and published integration contracts.

- **PAY-FE-04.1** Given an authorised user and configured business context: Submit permitted bank changes. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-04.2** Given an authorised user and configured business context: Submit tax declarations. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-04.3** Given an authorised user and configured business context: Submit payment preferences. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-04.4** Given an authorised user and configured business context: Track approval status. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-FE-04.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-05 — Pay components

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Pay components workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Pay components aggregate, use cases, migrations and published integration contracts.

- **PAY-FE-05.1** Given an authorised user and configured business context: Maintain salary. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-05.2** Given an authorised user and configured business context: Maintain allowances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-05.3** Given an authorised user and configured business context: Maintain bonuses. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-05.4** Given an authorised user and configured business context: Maintain deductions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-05.5** Given an authorised user and configured business context: Maintain reimbursements. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-05.6** Given an authorised user and configured business context: Maintain employer contributions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-FE-05.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-06 — Payroll inputs

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Payroll inputs workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Payroll inputs aggregate, use cases, migrations and published integration contracts.

- **PAY-FE-06.1** Given an authorised user and configured business context: Enter or upload variable pay. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-06.2** Given an authorised user and configured business context: Enter overtime. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-06.3** Given an authorised user and configured business context: Enter unpaid leave. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-06.4** Given an authorised user and configured business context: Enter incentives. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-06.5** Given an authorised user and configured business context: Enter one-time adjustments. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-FE-06.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-07 — Time and absence review

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Approved time selector and immutable day snapshot; absence, expected time and attendance ingestion remain missing.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-FE-07.1** Given an authorised user and configured business context: Display approved time inputs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-07.2** Given an authorised user and configured business context: Display attendance. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-07.3** Given an authorised user and configured business context: Display absence. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-07.4** Given an authorised user and configured business context: Highlight missing and unapproved inputs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-08 — Payroll execution

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Calculate/recalculate one worker from one whole approved timesheet; no pay groups or country calendar execution.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-FE-08.1** Given an authorised user and configured business context: Select pay groups and periods. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-08.2** Given an authorised user and configured business context: Validate inputs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-08.3** Given an authorised user and configured business context: Calculate payroll. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-08.4** Given an authorised user and configured business context: Recalculate affected employees. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-09 — Payroll preview

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Demonstration gross, fixed voluntary deduction and net. Tax/contributions explicitly unavailable.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-FE-09.1** Given an authorised user and configured business context: Show employee gross. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-09.2** Given an authorised user and configured business context: Show deductions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-09.3** Given an authorised user and configured business context: Show taxes. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-09.4** Given an authorised user and configured business context: Show employer contributions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-09.5** Given an authorised user and configured business context: Show net before finalization. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-10 — Variance and exception review

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Actionable blocking nonpositive-net exception; no prior-period variance engine.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-FE-10.1** Given an authorised user and configured business context: Compare previous periods. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-10.2** Given an authorised user and configured business context: Detect missing data. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-10.3** Given an authorised user and configured business context: Flag unusual amounts. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-10.4** Given an authorised user and configured business context: Explain calculation errors. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-11 — Payroll approvals

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Submit, independently finalize or return; finalization freezes results. Configurable approval stages absent.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-FE-11.1** Given an authorised user and configured business context: Review results. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-11.2** Given an authorised user and configured business context: Approve under configured authority. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-11.3** Given an authorised user and configured business context: Return with reason. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-11.4** Given an authorised user and configured business context: Finalize independently. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-12 — Retroactive adjustments

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Retroactive adjustments workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Retroactive adjustments aggregate, use cases, migrations and published integration contracts.

- **PAY-FE-12.1** Given an authorised user and configured business context: Display prior-period changes. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-12.2** Given an authorised user and configured business context: Display recalculated amounts. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-12.3** Given an authorised user and configured business context: Display arrears. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-12.4** Given an authorised user and configured business context: Display recovery details. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-FE-12.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-13 — Off-cycle and final pay

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Off-cycle and final pay workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Off-cycle and final pay aggregate, use cases, migrations and published integration contracts.

- **PAY-FE-13.1** Given an authorised user and configured business context: Process supplemental payments. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-13.2** Given an authorised user and configured business context: Process corrections. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-13.3** Given an authorised user and configured business context: Process termination settlements. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-FE-13.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-14 — Payslips and documents

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Released own demonstration text payslip download; annual documents absent.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-FE-14.1** Given an authorised user and configured business context: View released payslips. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-14.2** Given an authorised user and configured business context: Download released payslips. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-14.3** Given an authorised user and configured business context: Retrieve applicable annual payroll documents. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-14.4** Given an authorised user and configured business context: Retrieve applicable annual tax documents. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-15 — Payment monitoring

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Payment monitoring workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Payment monitoring aggregate, use cases, migrations and published integration contracts.

- **PAY-FE-15.1** Given an authorised user and configured business context: Display batches. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-15.2** Given an authorised user and configured business context: Display release approvals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-15.3** Given an authorised user and configured business context: Display acknowledgements. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-15.4** Given an authorised user and configured business context: Display rejection reasons. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-15.5** Given an authorised user and configured business context: Display reissue status. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-FE-15.*; not executed.

Internal payment/acknowledgement workflow and test adapter not implemented. Live provider additionally needs bank contract, credentials, identifiers and explicit transmission authorization.

## PAY-FE-16 — Payroll reports

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Persisted payroll register only; remaining reports/exports absent.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-FE-16.1** Given an authorised user and configured business context: Payroll register. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-16.2** Given an authorised user and configured business context: Deduction summaries. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-16.3** Given an authorised user and configured business context: Contribution reports. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-16.4** Given an authorised user and configured business context: Payment reports. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-16.5** Given an authorised user and configured business context: Labour-cost reports with scope controls. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-17 — Statutory reporting

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Statutory reporting workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Statutory reporting aggregate, use cases, migrations and published integration contracts.

- **PAY-FE-17.1** Given an authorised user and configured business context: Review country-specific reports. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-17.2** Given an authorised user and configured business context: Display submission status. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-17.3** Given an authorised user and configured business context: Display validation errors. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-FE-17.*; not executed.

Production blocked on selected jurisdictions and independently validated authoritative statutory rules/formats. Configurable rules framework is also incomplete internal engineering.

## PAY-FE-18 — Payroll administration

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Demonstration ledger setup and fixed posting map visible; effective payroll administration absent.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-FE-18.1** Given an authorised user and configured business context: Manage calendars. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-18.2** Given an authorised user and configured business context: Manage pay groups. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-18.3** Given an authorised user and configured business context: Manage calculation rules. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-18.4** Given an authorised user and configured business context: Manage approval policies. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-18.5** Given an authorised user and configured business context: Manage effective payroll-account mappings. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-19 — Payroll inquiries

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Payroll inquiries workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Payroll inquiries aggregate, use cases, migrations and published integration contracts.

- **PAY-FE-19.1** Given an authorised user and configured business context: Employee raises payroll inquiry. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-19.2** Given an authorised user and configured business context: Track responses. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-FE-19.3** Given an authorised user and configured business context: Track corrections. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-FE-19.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-FE-20 — History and notifications

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Restricted latest100 audit view; notification jobs absent.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-FE-20.1** Given an authorised user and configured business context: Display payroll history. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-20.2** Given an authorised user and configured business context: Show authorised audit. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-20.3** Given an authorised user and configured business context: Show reminders. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-20.4** Given an authorised user and configured business context: Show approval requests. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-FE-20.5** Given an authorised user and configured business context: Show processing failures. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-01 — Authorization

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Explicit subject+employee+entity grants; own finalized result access; current active identity validation.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-01.1** Given an authorised user and configured business context: Enforce employee ownership. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-01.2** Given an authorised user and configured business context: Enforce organisation and legal-entity boundaries. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-01.3** Given an authorised user and configured business context: Require payroll roles. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-01.4** Given an authorised user and configured business context: Require approval permissions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-02 — Employee synchronization

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Published current HR worker/episode/entity contract. Historical compensation, bank and tax not synchronized.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-02.1** Given an authorised user and configured business context: Effective-dated employee and employment data. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-02.2** Given an authorised user and configured business context: Effective-dated compensation. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-02.3** Given an authorised user and configured business context: Bank data synchronization. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-02.4** Given an authorised user and configured business context: Tax data synchronization. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-03 — Payroll eligibility

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Current active employment episode and entity match; no pay-group/country eligibility engine.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-03.1** Given an authorised user and configured business context: Resolve eligible workers. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-03.2** Given an authorised user and configured business context: Resolve pay groups. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-03.3** Given an authorised user and configured business context: Resolve payroll country. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-03.4** Given an authorised user and configured business context: Resolve reporting period. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-04 — Payroll calendars

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Payroll calendars workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Payroll calendars aggregate, use cases, migrations and published integration contracts.

- **PAY-BE-04.1** Given an authorised user and configured business context: Manage frequency. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-04.2** Given an authorised user and configured business context: Manage processing periods. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-04.3** Given an authorised user and configured business context: Manage cutoffs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-04.4** Given an authorised user and configured business context: Manage payment dates. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-04.5** Given an authorised user and configured business context: Lock period status. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-BE-04.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-05 — Input processing

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Approved-time synchronous published contract only; no direct cross-module table reads.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-05.1** Given an authorised user and configured business context: Import approved time. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-05.2** Given an authorised user and configured business context: Import absence. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-05.3** Given an authorised user and configured business context: Import compensation. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-05.4** Given an authorised user and configured business context: Import benefits. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-05.5** Given an authorised user and configured business context: Import expenses. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-05.6** Given an authorised user and configured business context: Import variable pay. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-05.7** Given an authorised user and configured business context: Validate every source. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-06 — Gross-to-net calculation

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

DEMO-HOURLY-1 exact decimal minutes × entered rate /60, half-even to USD cents, voluntary fixed deduction. No statutory gross-to-net.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-06.1** Given an authorised user and configured business context: Calculate earnings. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-06.2** Given an authorised user and configured business context: Calculate deductions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-06.3** Given an authorised user and configured business context: Apply configured taxes. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-06.4** Given an authorised user and configured business context: Apply employer contributions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-06.5** Given an authorised user and configured business context: Calculate net exactly. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-07 — Proration and rounding

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Explicit half-even fixture rounding only; configured proration missing.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-07.1** Given an authorised user and configured business context: Prorate partial periods. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-07.2** Given an authorised user and configured business context: Handle joining. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-07.3** Given an authorised user and configured business context: Handle termination. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-07.4** Given an authorised user and configured business context: Handle salary changes. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-07.5** Given an authorised user and configured business context: Handle unpaid leave. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-07.6** Given an authorised user and configured business context: Apply configured rounding. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-08 — Statutory calculations

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Statutory calculations workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Statutory calculations aggregate, use cases, migrations and published integration contracts.

- **PAY-BE-08.1** Given an authorised user and configured business context: Version country thresholds. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-08.2** Given an authorised user and configured business context: Apply effective contributions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-08.3** Given an authorised user and configured business context: Apply tax rules. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-08.4** Given an authorised user and configured business context: Apply reporting requirements. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-BE-08.*; not executed.

Production blocked on selected jurisdictions and independently validated authoritative statutory rules/formats. Configurable rules framework is also incomplete internal engineering.

## PAY-BE-09 — Deduction processing

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Single voluntary fixed deduction per calculation only.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-09.1** Given an authorised user and configured business context: Recurring deductions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-09.2** Given an authorised user and configured business context: One-time deductions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-09.3** Given an authorised user and configured business context: Deduction priorities. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-09.4** Given an authorised user and configured business context: Deduction limits. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-09.5** Given an authorised user and configured business context: Balances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-09.6** Given an authorised user and configured business context: Permitted carry-forward. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-10 — Retroactive calculation

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Retroactive calculation workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Retroactive calculation aggregate, use cases, migrations and published integration contracts.

- **PAY-BE-10.1** Given an authorised user and configured business context: Detect historical changes. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-10.2** Given an authorised user and configured business context: Recalculate original period using snapshots. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-10.3** Given an authorised user and configured business context: Compute payable differences. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-10.4** Given an authorised user and configured business context: Compute recoverable differences. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-BE-10.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-11 — Off-cycle and final payroll

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Off-cycle and final payroll workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Off-cycle and final payroll aggregate, use cases, migrations and published integration contracts.

- **PAY-BE-11.1** Given an authorised user and configured business context: Supplemental runs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-11.2** Given an authorised user and configured business context: Corrective runs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-11.3** Given an authorised user and configured business context: Reversals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-11.4** Given an authorised user and configured business context: Final settlements. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-BE-11.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-12 — Validation and exceptions

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Source duplication, eligibility, revision, dimension and nonpositive-net controls; bank/variance checks absent.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-12.1** Given an authorised user and configured business context: Detect missing inputs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-12.2** Given an authorised user and configured business context: Detect duplicate payment. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-12.3** Given an authorised user and configured business context: Detect unusual variance. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-12.4** Given an authorised user and configured business context: Validate banking. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-12.5** Given an authorised user and configured business context: Apply configured blocking or warning rules. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-13 — Payroll workflow

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

CALCULATED → SUBMITTED → FINALIZED/RETURNED; editable returned inputs; finalized correction/reopen unavailable.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-13.1** Given an authorised user and configured business context: Validate. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-13.2** Given an authorised user and configured business context: Calculate. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-13.3** Given an authorised user and configured business context: Review. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-13.4** Given an authorised user and configured business context: Approve. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-13.5** Given an authorised user and configured business context: Finalize. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-13.6** Given an authorised user and configured business context: Authorised reopen without loss of history. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-14 — Payroll results

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Frozen approved-time, rate, result and rule snapshots; YTD balances absent.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-14.1** Given an authorised user and configured business context: Preserve results. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-14.2** Given an authorised user and configured business context: Preserve input snapshots. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-14.3** Given an authorised user and configured business context: Preserve rule versions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-14.4** Given an authorised user and configured business context: Maintain year-to-date balances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-15 — Payslip generation

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Finalization releases demonstration text payslip; retrieval audited and scoped; no annual output.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-15.1** Given an authorised user and configured business context: Generate finalized payslip. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-15.2** Given an authorised user and configured business context: Release under policy. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-15.3** Given an authorised user and configured business context: Generate applicable annual documents. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-15.4** Given an authorised user and configured business context: Restrict retrieval. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-16 — Payment processing

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Payment processing workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Payment processing aggregate, use cases, migrations and published integration contracts.

- **PAY-BE-16.1** Given an authorised user and configured business context: Generate instructions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-16.2** Given an authorised user and configured business context: Authorise release. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-16.3** Given an authorised user and configured business context: Process bank acknowledgements. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-16.4** Given an authorised user and configured business context: Process failures and safe reissue. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-BE-16.*; not executed.

Internal payment/acknowledgement workflow and test adapter not implemented. Live provider additionally needs bank contract, credentials, identifiers and explicit transmission authorization.

## PAY-BE-17 — Statutory outputs

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Statutory outputs workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Statutory outputs aggregate, use cases, migrations and published integration contracts.

- **PAY-BE-17.1** Given an authorised user and configured business context: Generate filings. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-17.2** Given an authorised user and configured business context: Generate remittance outputs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-17.3** Given an authorised user and configured business context: Track acceptance. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-17.4** Given an authorised user and configured business context: Track rejection. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-17.5** Given an authorised user and configured business context: Correct outputs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-BE-17.*; not executed.

Production blocked on selected jurisdictions and independently validated authoritative statutory rules/formats. Configurable rules framework is also incomplete internal engineering.

## PAY-BE-18 — Payroll accounting

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Fixed demonstration expense/deduction/net mapping with one configured cost centre; effective mapping UI missing.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-18.1** Given an authorised user and configured business context: Map earnings expenses. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-18.2** Given an authorised user and configured business context: Map deduction liabilities. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-18.3** Given an authorised user and configured business context: Map tax and contribution liabilities. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-18.4** Given an authorised user and configured business context: Map net pay and clearing. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-18.5** Given an authorised user and configured business context: Apply accounting dimensions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-19 — Reconciliation

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Gross=deduction+net and actual balanced payroll journal totals; payments/banks/statutory remain unimplemented.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-19.1** Given an authorised user and configured business context: Payroll to instructions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-19.2** Given an authorised user and configured business context: Instructions to bank. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-19.3** Given an authorised user and configured business context: Payroll to statutory amounts. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-19.4** Given an authorised user and configured business context: Payroll to ledger totals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-20 — Corrections and duplicate prevention

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Unique consumed timesheet and unique payroll source journal; idempotent commands. No payment/reissue/retro workflow.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-20.1** Given an authorised user and configured business context: Controlled reversal. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-20.2** Given an authorised user and configured business context: Controlled reissue. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-20.3** Given an authorised user and configured business context: Prevent duplicate runs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-20.4** Given an authorised user and configured business context: Prevent duplicate payments. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-20.5** Given an authorised user and configured business context: Prevent duplicate postings. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-21 — Audit and retention

Frontend: **partial**. Backend: **partial**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Immutable before/after/access audit; approved retention schedule and legal holds missing.

Screen: Payroll register / calculation editor / result detail / audit / released payslip. API/use case: /api/v1/payroll/runs, runs/{id}/actions, runs/{id}/payslip, runs/{id}/history. Data/domain: PayrollService / PayrollCalculation / pay_run, pay_command, pay_audit / published HR, time and finance contracts.

- **PAY-BE-21.1** Given an authorised user and configured business context: Record authorised access. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-21.2** Given an authorised user and configured business context: Record changes. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-21.3** Given an authorised user and configured business context: Record approvals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-21.4** Given an authorised user and configured business context: Apply configured retention. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **PAY-BE-21.5** Given an authorised user and configured business context: Respect legal holds. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## PAY-BE-22 — Reports and notifications

Frontend: **not implemented**. Backend: **not implemented**. Roles: Employee owner; explicitly granted payroll preparer and independent reviewer. Original ERP phases: 4, 7.

Missing application capability; retained in committed scope.

Screen: Planned Reports and notifications workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Reports and notifications aggregate, use cases, migrations and published integration contracts.

- **PAY-BE-22.1** Given an authorised user and configured business context: Authorised payroll reports. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-22.2** Given an authorised user and configured business context: Workflow notifications. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-22.3** Given an authorised user and configured business context: Deadline reminders. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **PAY-BE-22.4** Given an authorised user and configured business context: Exception notifications. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks PAY-BE-22.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-01 — Finance dashboard

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Finance dashboard workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Finance dashboard aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-01.1** Given an authorised user and configured business context: Cash balances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-01.2** Given an authorised user and configured business context: Receivables. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-01.3** Given an authorised user and configured business context: Payables. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-01.4** Given an authorised user and configured business context: Expenses. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-01.5** Given an authorised user and configured business context: Financial results. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-01.6** Given an authorised user and configured business context: Close progress by scope. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-01.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-02 — Accounting master data

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Uses existing declared legal entity, fixed four-account USD chart and entered cost-centre codes; general master-data amendments missing.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-FE-02.1** Given an authorised user and configured business context: Manage existing companies. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-02.2** Given an authorised user and configured business context: Manage ledger accounts. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-02.3** Given an authorised user and configured business context: Manage cost centres. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-02.4** Given an authorised user and configured business context: Manage profit centres. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-02.5** Given an authorised user and configured business context: Manage projects and dimensions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-03 — Journal entry

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Manual create/edit/submit connected; uploads and attachments absent.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-FE-03.1** Given an authorised user and configured business context: Create manual journal. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-03.2** Given an authorised user and configured business context: Edit permitted draft. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-03.3** Given an authorised user and configured business context: Upload journal. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-03.4** Given an authorised user and configured business context: Submit journal. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-03.5** Given an authorised user and configured business context: Attach supporting document. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-04 — Journal approvals

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Independent submit/review/return/reject/post; manual reversal is a new reviewed/posted counter-entry.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-FE-04.1** Given an authorised user and configured business context: Independent review. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-04.2** Given an authorised user and configured business context: Approve. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-04.3** Given an authorised user and configured business context: Return. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-04.4** Given an authorised user and configured business context: Post. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-04.5** Given an authorised user and configured business context: Prepare and authorise reversal. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-05 — Ledger inquiry

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Status-filtered journal pages, source IDs and date-filtered trial balance; search/drill-down expansion missing.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-FE-05.1** Given an authorised user and configured business context: Search balances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-05.2** Given an authorised user and configured business context: Search transactions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-05.3** Given an authorised user and configured business context: Drill to original source. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-06 — Supplier invoices

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Supplier invoices workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Supplier invoices aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-06.1** Given an authorised user and configured business context: Enter invoice. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-06.2** Given an authorised user and configured business context: Upload invoice. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-06.3** Given an authorised user and configured business context: Review and approve. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-06.4** Given an authorised user and configured business context: Process credit note. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-06.*; not executed.

Internal procurement/invoicing/expense source capabilities and cross-channel reimbursement ownership must be implemented; this is not merely a credential blocker.

## ACC-FE-07 — Invoice matching

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Invoice matching workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Invoice matching aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-07.1** Given an authorised user and configured business context: Compare purchase order. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-07.2** Given an authorised user and configured business context: Compare receipt. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-07.3** Given an authorised user and configured business context: Compare invoice. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-07.4** Given an authorised user and configured business context: Resolve differences. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-07.*; not executed.

Internal procurement/invoicing/expense source capabilities and cross-channel reimbursement ownership must be implemented; this is not merely a credential blocker.

## ACC-FE-08 — Accounts payable

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Accounts payable workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Accounts payable aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-08.1** Given an authorised user and configured business context: Supplier balances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-08.2** Given an authorised user and configured business context: Due invoices. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-08.3** Given an authorised user and configured business context: Aging. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-08.4** Given an authorised user and configured business context: Payment proposals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-08.5** Given an authorised user and configured business context: Payment status. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-08.*; not executed.

Internal procurement/invoicing/expense source capabilities and cross-channel reimbursement ownership must be implemented; this is not merely a credential blocker.

## ACC-FE-09 — Customer billing

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Customer billing workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Customer billing aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-09.1** Given an authorised user and configured business context: Create invoice. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-09.2** Given an authorised user and configured business context: Review invoice. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-09.3** Given an authorised user and configured business context: Credit notes. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-09.4** Given an authorised user and configured business context: Billing adjustments. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-09.*; not executed.

Internal procurement/invoicing/expense source capabilities and cross-channel reimbursement ownership must be implemented; this is not merely a credential blocker.

## ACC-FE-10 — Accounts receivable

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Accounts receivable workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Accounts receivable aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-10.1** Given an authorised user and configured business context: Customer balances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-10.2** Given an authorised user and configured business context: Aging. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-10.3** Given an authorised user and configured business context: Incoming payments. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-10.4** Given an authorised user and configured business context: Disputes. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-10.5** Given an authorised user and configured business context: Collections. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-10.*; not executed.

Internal procurement/invoicing/expense source capabilities and cross-channel reimbursement ownership must be implemented; this is not merely a credential blocker.

## ACC-FE-11 — Expense management

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Expense management workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Expense management aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-11.1** Given an authorised user and configured business context: Employee submits claim. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-11.2** Given an authorised user and configured business context: Attach receipts. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-11.3** Given an authorised user and configured business context: Approve claims. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-11.4** Given an authorised user and configured business context: Return claims. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-11.*; not executed.

Internal procurement/invoicing/expense source capabilities and cross-channel reimbursement ownership must be implemented; this is not merely a credential blocker.

## ACC-FE-12 — Bank reconciliation

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Bank reconciliation workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Bank reconciliation aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-12.1** Given an authorised user and configured business context: Display statement lines. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-12.2** Given an authorised user and configured business context: Suggest matches. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-12.3** Given an authorised user and configured business context: Show unmatched transactions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-12.4** Given an authorised user and configured business context: Review adjustments. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-12.*; not executed.

Internal payment/acknowledgement workflow and test adapter not implemented. Live provider additionally needs bank contract, credentials, identifiers and explicit transmission authorization.

## ACC-FE-13 — Fixed assets

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Fixed assets workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Fixed assets aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-13.1** Given an authorised user and configured business context: Acquire asset. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-13.2** Given an authorised user and configured business context: Capitalize. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-13.3** Given an authorised user and configured business context: Review depreciation. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-13.4** Given an authorised user and configured business context: Transfer. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-13.5** Given an authorised user and configured business context: Impair. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-13.6** Given an authorised user and configured business context: Dispose. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-13.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-14 — Accruals and allocations

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Accruals and allocations workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Accruals and allocations aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-14.1** Given an authorised user and configured business context: Define recurring journals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-14.2** Given an authorised user and configured business context: Review accruals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-14.3** Given an authorised user and configured business context: Execute prepayments. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-14.4** Given an authorised user and configured business context: Execute approved cost allocations. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-14.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-15 — Intercompany accounting

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Intercompany accounting workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Intercompany accounting aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-15.1** Given an authorised user and configured business context: Transactions and counterparties. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-15.2** Given an authorised user and configured business context: Balances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-15.3** Given an authorised user and configured business context: Reconciliation differences. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-15.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-16 — Currency management

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Currency management workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Currency management aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-16.1** Given an authorised user and configured business context: Maintain authorised rates. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-16.2** Given an authorised user and configured business context: Review currency revaluations. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-16.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-17 — Tax accounting

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Tax accounting workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Tax accounting aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-17.1** Given an authorised user and configured business context: Show configured transaction tax. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-17.2** Given an authorised user and configured business context: Show withholding. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-17.3** Given an authorised user and configured business context: Show balances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-17.4** Given an authorised user and configured business context: Show reporting outputs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-17.*; not executed.

Production blocked on selected jurisdictions and independently validated authoritative statutory rules/formats. Configurable rules framework is also incomplete internal engineering.

## ACC-FE-18 — Period close

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Versioned close/open control with reasons and impact; no close-task checklist.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-FE-18.1** Given an authorised user and configured business context: Display close tasks. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-18.2** Given an authorised user and configured business context: Display dependencies. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-18.3** Given an authorised user and configured business context: Display reconciliations. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-18.4** Given an authorised user and configured business context: Display approvals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-18.5** Given an authorised user and configured business context: Display locks. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-19 — Financial statements

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Actual persisted trial balance; full financial statements missing.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-FE-19.1** Given an authorised user and configured business context: Balance sheet. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-19.2** Given an authorised user and configured business context: Income statement. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-19.3** Given an authorised user and configured business context: Cash flow. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-19.4** Given an authorised user and configured business context: Trial balance. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-19.5** Given an authorised user and configured business context: Comparative reporting. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-20 — Consolidation

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Consolidation workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Consolidation aggregate, use cases, migrations and published integration contracts.

- **ACC-FE-20.1** Given an authorised user and configured business context: Review entity submissions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-20.2** Given an authorised user and configured business context: Review consolidation adjustments. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-20.3** Given an authorised user and configured business context: Review eliminations. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-FE-20.4** Given an authorised user and configured business context: Review consolidated results. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-FE-20.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-21 — Payroll accounting review

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Actual payroll journal, expense dimension and liability lines; no consolidated reconciliation dashboard.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-FE-21.1** Given an authorised user and configured business context: Display source journals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-21.2** Given an authorised user and configured business context: Display cost allocation. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-21.3** Given an authorised user and configured business context: Display liabilities. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-21.4** Given an authorised user and configured business context: Display posting errors. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-21.5** Given an authorised user and configured business context: Display reconciliation status. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-FE-22 — Audit and integration monitoring

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Paginated immutable financial history and safe retry errors; no import/processing monitor.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-FE-22.1** Given an authorised user and configured business context: Document history. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-22.2** Given an authorised user and configured business context: Approval trail. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-22.3** Given an authorised user and configured business context: Failed imports. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-22.4** Given an authorised user and configured business context: Posting errors. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-FE-22.5** Given an authorised user and configured business context: Authorised retries. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-01 — Authorization and separation of duties

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Subject+employee+entity action grants; mandatory three-person prepare/approve/post; no approval thresholds/delegation.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-BE-01.1** Given an authorised user and configured business context: Company scope. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-01.2** Given an authorised user and configured business context: Ledger scope. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-01.3** Given an authorised user and configured business context: Role and action grants. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-01.4** Given an authorised user and configured business context: Prevent prepare and approve identity overlap. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-01.5** Given an authorised user and configured business context: Apply configured authority. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-02 — Accounting configuration

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Single demonstration USD ledger per existing legal entity, four fixed accounts and finite cost-centre list; full effective configuration missing.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-BE-02.1** Given an authorised user and configured business context: Chart of accounts. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-02.2** Given an authorised user and configured business context: Ledgers. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-02.3** Given an authorised user and configured business context: Reuse legal entities. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-02.4** Given an authorised user and configured business context: Fiscal calendars. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-02.5** Given an authorised user and configured business context: Currencies. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-02.6** Given an authorised user and configured business context: Accounting dimensions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-03 — Journal validation

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Balanced exact decimals, entity accounts, required cost centre, currency and open effective posting date; attachment policy missing.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-BE-03.1** Given an authorised user and configured business context: Exact balanced amounts. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-03.2** Given an authorised user and configured business context: Valid accounts. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-03.3** Given an authorised user and configured business context: Valid dimensions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-03.4** Given an authorised user and configured business context: Valid currency. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-03.5** Given an authorised user and configured business context: Supporting-document policy. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-03.6** Given an authorised user and configured business context: Open period. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-04 — Journal lifecycle

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Persisted state machine plus database guards, independent approval, opposite-line manual reversal.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-BE-04.1** Given an authorised user and configured business context: Draft. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-04.2** Given an authorised user and configured business context: Submitted. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-04.3** Given an authorised user and configured business context: Approved. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-04.4** Given an authorised user and configured business context: Posted. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-04.5** Given an authorised user and configured business context: Rejected or returned. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-04.6** Given an authorised user and configured business context: Controlled reversal. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-05 — General ledger

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Posted/reversed journal snapshots form ledger, with report projection and source IDs; no AP/AR subledgers.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-BE-05.1** Given an authorised user and configured business context: Persist posted transactions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-05.2** Given an authorised user and configured business context: Calculate balances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-05.3** Given an authorised user and configured business context: Preserve source traceability. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-05.4** Given an authorised user and configured business context: Preserve subledger traceability. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-06 — Accounts payable

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Accounts payable workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Accounts payable aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-06.1** Given an authorised user and configured business context: Supplier invoices. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-06.2** Given an authorised user and configured business context: Credit notes. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-06.3** Given an authorised user and configured business context: Due dates. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-06.4** Given an authorised user and configured business context: Payment terms. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-06.5** Given an authorised user and configured business context: Holds. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-06.6** Given an authorised user and configured business context: Outstanding balances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-06.*; not executed.

Internal procurement/invoicing/expense source capabilities and cross-channel reimbursement ownership must be implemented; this is not merely a credential blocker.

## ACC-BE-07 — Invoice matching

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Invoice matching workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Invoice matching aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-07.1** Given an authorised user and configured business context: Match purchase order. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-07.2** Given an authorised user and configured business context: Match receipt. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-07.3** Given an authorised user and configured business context: Apply tolerances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-07.4** Given an authorised user and configured business context: Resolve exceptions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-07.*; not executed.

Internal procurement/invoicing/expense source capabilities and cross-channel reimbursement ownership must be implemented; this is not merely a credential blocker.

## ACC-BE-08 — Supplier payments

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Supplier payments workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Supplier payments aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-08.1** Given an authorised user and configured business context: Generate proposal. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-08.2** Given an authorised user and configured business context: Generate instruction. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-08.3** Given an authorised user and configured business context: Require approval. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-08.4** Given an authorised user and configured business context: Track settlement. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-08.5** Given an authorised user and configured business context: Handle rejection. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-08.6** Given an authorised user and configured business context: Safe reissue. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-08.*; not executed.

Internal payment/acknowledgement workflow and test adapter not implemented. Live provider additionally needs bank contract, credentials, identifiers and explicit transmission authorization. Internal procurement/invoicing/expense source capabilities and cross-channel reimbursement ownership must be implemented; this is not merely a credential blocker.

## ACC-BE-09 — Accounts receivable

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Accounts receivable workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Accounts receivable aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-09.1** Given an authorised user and configured business context: Customer invoices. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-09.2** Given an authorised user and configured business context: Credit notes. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-09.3** Given an authorised user and configured business context: Receipts. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-09.4** Given an authorised user and configured business context: Allocations. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-09.5** Given an authorised user and configured business context: Outstanding balances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-09.6** Given an authorised user and configured business context: Write-offs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-09.*; not executed.

Internal procurement/invoicing/expense source capabilities and cross-channel reimbursement ownership must be implemented; this is not merely a credential blocker.

## ACC-BE-10 — Collections

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Collections workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Collections aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-10.1** Given an authorised user and configured business context: Calculate aging. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-10.2** Given an authorised user and configured business context: Send reminders. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-10.3** Given an authorised user and configured business context: Track dispute. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-10.4** Given an authorised user and configured business context: Track follow-up. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-10.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-11 — Employee expenses

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Employee expenses workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Employee expenses aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-11.1** Given an authorised user and configured business context: Validate claim. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-11.2** Given an authorised user and configured business context: Apply approvals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-11.3** Given an authorised user and configured business context: Calculate reimbursement. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-11.4** Given an authorised user and configured business context: Route payment once across payroll and AP. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-11.*; not executed.

Internal procurement/invoicing/expense source capabilities and cross-channel reimbursement ownership must be implemented; this is not merely a credential blocker.

## ACC-BE-12 — Bank reconciliation

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Bank reconciliation workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Bank reconciliation aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-12.1** Given an authorised user and configured business context: Import statements. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-12.2** Given an authorised user and configured business context: Match transactions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-12.3** Given an authorised user and configured business context: Identify differences. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-12.4** Given an authorised user and configured business context: Post authorised adjustments. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-12.*; not executed.

Internal payment/acknowledgement workflow and test adapter not implemented. Live provider additionally needs bank contract, credentials, identifiers and explicit transmission authorization.

## ACC-BE-13 — Asset accounting

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Asset accounting workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Asset accounting aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-13.1** Given an authorised user and configured business context: Values and acquisition. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-13.2** Given an authorised user and configured business context: Capitalization. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-13.3** Given an authorised user and configured business context: Depreciation. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-13.4** Given an authorised user and configured business context: Transfer. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-13.5** Given an authorised user and configured business context: Impairment. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-13.6** Given an authorised user and configured business context: Disposal. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-13.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-14 — Accruals and prepayments

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Accruals and prepayments workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Accruals and prepayments aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-14.1** Given an authorised user and configured business context: Scheduled recognition. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-14.2** Given an authorised user and configured business context: Generate journals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-14.3** Given an authorised user and configured business context: Configured reversals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-14.*; not executed.

Internal payment/acknowledgement workflow and test adapter not implemented. Live provider additionally needs bank contract, credentials, identifiers and explicit transmission authorization.

## ACC-BE-15 — Cost allocations

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Cost allocations workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Cost allocations aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-15.1** Given an authorised user and configured business context: Configure dimensions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-15.2** Given an authorised user and configured business context: Approve allocation rules. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-15.3** Given an authorised user and configured business context: Apply drivers. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-15.4** Given an authorised user and configured business context: Reconcile distributed totals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-15.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-16 — Intercompany processing

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Intercompany processing workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Intercompany processing aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-16.1** Given an authorised user and configured business context: Linked intercompany entries. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-16.2** Given an authorised user and configured business context: Balance reconciliation. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-16.3** Given an authorised user and configured business context: Settlement. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-16.4** Given an authorised user and configured business context: Consolidation eliminations. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-16.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-17 — Multicurrency accounting

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Multicurrency accounting workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Multicurrency accounting aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-17.1** Given an authorised user and configured business context: Apply rates. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-17.2** Given an authorised user and configured business context: Transaction-currency amount. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-17.3** Given an authorised user and configured business context: Accounting-currency amount. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-17.4** Given an authorised user and configured business context: Exchange gains and losses. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-17.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-18 — Tax accounting

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Tax accounting workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Tax accounting aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-18.1** Given an authorised user and configured business context: Configured transaction tax. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-18.2** Given an authorised user and configured business context: Configured withholding. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-18.3** Given an authorised user and configured business context: Accounting outputs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-18.4** Given an authorised user and configured business context: Reporting outputs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-18.*; not executed.

Production blocked on selected jurisdictions and independently validated authoritative statutory rules/formats. Configurable rules framework is also incomplete internal engineering.

## ACC-BE-19 — Period management

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Versioned fiscal-month controls serialized with posting; controlled reopen retains history.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-BE-19.1** Given an authorised user and configured business context: Open periods. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-19.2** Given an authorised user and configured business context: Close periods. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-19.3** Given an authorised user and configured business context: Enforce posting restrictions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-19.4** Given an authorised user and configured business context: Authorised reopening with history. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-20 — Financial close

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Financial close workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Financial close aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-20.1** Given an authorised user and configured business context: Activities. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-20.2** Given an authorised user and configured business context: Dependencies. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-20.3** Given an authorised user and configured business context: Reconciliations. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-20.4** Given an authorised user and configured business context: Exceptions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-20.5** Given an authorised user and configured business context: Completion approvals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-20.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-21 — Consolidation

Frontend: **not implemented**. Backend: **not implemented**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Missing application capability; retained in committed scope.

Screen: Planned Consolidation workflow. API/use case: Planned capability-specific API; no working endpoint claimed. Data/domain: Planned owned Consolidation aggregate, use cases, migrations and published integration contracts.

- **ACC-BE-21.1** Given an authorised user and configured business context: Aggregate entity results. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-21.2** Given an authorised user and configured business context: Translate currencies. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-21.3** Given an authorised user and configured business context: Apply adjustments. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-21.4** Given an authorised user and configured business context: Apply eliminations. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed
- **ACC-BE-21.5** Given an authorised user and configured business context: Consolidated balances. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — planned, not executed

Checks: Planned checks ACC-BE-21.*; not executed.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-22 — Financial reporting

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Date-filtered trial balance from owned posted records only; statements and broader reporting missing.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-BE-22.1** Given an authorised user and configured business context: Financial statements. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-22.2** Given an authorised user and configured business context: Management reporting. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-22.3** Given an authorised user and configured business context: Drill to persisted transactions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-23 — Payroll posting

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Trusted internal finalized payroll contract creates one balanced draft; separate financial review and posting.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-BE-23.1** Given an authorised user and configured business context: Receive approved results. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-23.2** Given an authorised user and configured business context: Validate mappings. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-23.3** Given an authorised user and configured business context: Create balanced journals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-23.4** Given an authorised user and configured business context: Reconcile expenses liabilities and clearing. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-24 — Integration and duplicate prevention

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Unique payroll/reversal source IDs and actor-scoped immutable command receipts; invoices/payments not implemented.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-BE-24.1** Given an authorised user and configured business context: Import transactions. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-24.2** Given an authorised user and configured business context: Validate source IDs. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-24.3** Given an authorised user and configured business context: Prevent duplicate invoices. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-24.4** Given an authorised user and configured business context: Prevent duplicate payments. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-24.5** Given an authorised user and configured business context: Prevent duplicate postings. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-25 — Corrections and audit

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Posted records protected from edits/deletes; reviewed manual counter-entry and immutable audit; payroll corrections blocked pending payroll adjustment workflow.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-BE-25.1** Given an authorised user and configured business context: Preserve posted history. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-25.2** Given an authorised user and configured business context: Authorise reversal. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-25.3** Given an authorised user and configured business context: Authorise adjustment. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-25.4** Given an authorised user and configured business context: Complete audit trail. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

## ACC-BE-26 — Processing reconciliation

Frontend: **partial**. Backend: **partial**. Roles: Entity-scoped finance preparer, independent approver, independent poster, setup user and reader. Original ERP phases: 4, 6, 7.

Synchronous payroll/journal control totals, rollback and retry after closed-period rejection; no bank/subledger acknowledgements.

Screen: Finance journals / journal editor and detail / period controls / trial balance / configuration / history. API/use case: /api/v1/finance/context, ledgers, journals, journals/{id}/actions, period, periods, trial-balance, history. Data/domain: FinanceService / AccountingPolicy / fin_ledger, fin_period, fin_journal, fin_command, fin_audit / explicit fin_grant.

- **ACC-BE-26.1** Given an authorised user and configured business context: Track acknowledgements. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-26.2** Given an authorised user and configured business context: Track failures. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-26.3** Given an authorised user and configured business context: Authorised retries. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset
- **ACC-BE-26.4** Given an authorised user and configured business context: Reconcile source subledger bank and ledger totals. Verify persisted outcome, negative permission cases, validation and relevant historical/concurrent behaviour. — not individually signed off; see targeted tests for implemented subset

Checks: PayrollAccountingWorkflowTest, PayrollCalculationTest, ArchitectureTest, tests/payroll-accounting.spec.ts.

Missing internal behaviours remain implementation work. Production policy, scale, retention and deployment decisions remain open.

