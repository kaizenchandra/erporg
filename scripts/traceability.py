"""Source inventory and independently testable criteria; no completion inferred from screen existence."""
import json
from pathlib import Path
# Each semicolon-separated criterion is independently tracked, including source behaviour.
rows = [
('HR-01','Employee records','HR, employee',1,'people','Employee, EmploymentVersion','View and maintain personal information;View and maintain contact information;View and maintain emergency contacts;View and maintain employment information','Assign unique employee identifiers;Validate records;Retain effective-dated changes'),
('HR-02','Organisation structure','HR, manager',2,'organisation','Department, Position, ReportingLine','Browse organisation charts;Browse departments;Browse positions;Browse reporting relationships','Maintain hierarchies;Maintain position assignments;Validate reporting rules and cycles'),
('HR-03','Employee self-service','employee, approver',1,'people/profile','ChangeRequest','Submit personal information changes;Track approval status','Route requests for approval;Apply approved changes on correct date'),
('HR-04','Manager self-service','manager, approver',1,'people/team','ChangeRequest, EmploymentVersion','View authorised team records;Initiate transfers;Initiate promotions;Initiate job changes','Enforce reporting-line access;Validate proposed employment changes'),
('HR-05','Employment lifecycle','HR, IT, payroll',2,'lifecycle','Employment, LifecycleRequest','Process hiring;Process rehire;Process transfer;Process resignation;Process termination','Maintain employment status;Trigger dependent HR tasks;Trigger payroll workflows;Trigger access workflows'),
('HR-06','Benefits','employee, HR, payroll',2,'benefits','Plan, Dependant, Enrolment','View eligible plans;Enrol dependants;Submit selections','Evaluate eligibility;Calculate contributions;Pass deductions to payroll'),
('TS-01','Attendance','employee, manager',3,'attendance','Punch, Correction','Record attendance;Correct missed entries','Validate punches;Detect missing entries;Detect duplicate entries'),
('TS-02','Timesheets','employee, manager',3,'timesheets','Timesheet, TimeEntry','Enter working hours;Submit for approval','Validate hours;Transfer approved time to payroll;Transfer approved time to costing'),
('TS-03','Leave','employee, manager',3,'leave','LeaveRequest, Balance, Calendar','Request leave;Cancel leave;View balances;View team calendars','Calculate accruals;Enforce eligibility;Apply holiday rules;Apply carry-forward rules'),
('TS-04','Overtime','employee, manager',3,'overtime','OvertimeRequest','Submit overtime;Approve overtime','Apply overtime rules;Calculate approved payable hours'),
('DS-01','Documents','employee, HR',2,'documents','Document, Version, RetentionPolicy','Upload authorised documents;Search authorised documents;Download authorised documents','Manage versions;Manage templates;Enforce access;Apply retention;Check completeness'),
('DS-02','HR support','employee, caseworker',3,'support','Case, Correspondence, SLA','Search guidance;Create cases;Track cases','Assign cases;Maintain correspondence;Escalate overdue requests'),
('PY-01','Payroll setup','payroll admin',4,'payroll/setup','PayComponent, PayrollGroup, Calendar','Maintain pay components;Maintain payroll groups;Maintain calendars','Store effective-dated earnings;Store effective-dated deductions;Store effective-dated configuration'),
('PY-02','Payroll inputs','payroll operator',4,'payroll/inputs','PayrollInput, Snapshot','Review salary changes;Review variable payments;Review time;Review deductions','Validate inputs;Detect missing data;Detect inconsistent data'),
('PY-03','Payroll calculation','payroll operator',4,'payroll/runs','PayrollRun, RuleVersion, Result','Run simulations;Inspect employee calculation results','Calculate gross-to-net with configured country rules;Apply company rules'),
('PY-04','Exceptions','payroll operator',4,'payroll/exceptions','Exception, CorrectionTask','Review errors;Review variances;Review correction tasks','Detect exceptions;Block release while blocking issues remain'),
('PY-05','Retroactive payments','payroll operator',4,'payroll/retro','RetroAdjustment','Review backdated changes;Review adjustment results','Recalculate affected periods;Determine adjustment amounts'),
('PY-06','Off-cycle payroll','payroll operator, approver',4,'payroll/off-cycle','OffCycleRun','Initiate approved special payments;Initiate corrective payments','Process off-cycle runs;Reconcile with payroll history'),
('PY-07','Payroll release','payroll approver',4,'payroll/release','Release, Approval','Review totals;Approve completion','Enforce approval stages;Prevent duplicate payment processing'),
('PY-08','Employee outputs','employee, payroll',4,'payslips','Payslip, AnnualStatement','View payslips;Download payslips;View applicable annual statements;Download applicable annual statements','Generate employee outputs;Restrict access to authorised users'),
('PY-09','Payment and accounting','treasury, accountant',4,'payroll/reconciliation','PaymentInstruction, Journal, Reconciliation','Track payment files;Track posting status','Generate payment instructions;Generate accounting entries;Reconcile payments and postings'),
('TM-01','Recruitment','recruiter, manager',5,'recruitment','Requisition, Candidate, Interview, Offer','Create requisitions;Review candidates;Manage interviews;Manage offers','Route hiring approvals;Track application stages'),
('TM-02','Onboarding','new hire, HR',5,'onboarding','JoiningTask, Dependency','Complete new-hire forms;Complete assigned joining tasks','Create employee records;Coordinate task dependencies'),
('TM-03','Performance','employee, manager',5,'performance','Goal, ReviewCycle, Feedback','Set goals;Submit feedback;Complete reviews','Configure review cycles;Apply rating rules;Execute approval workflows'),
('TM-04','Learning','employee, learning admin',5,'learning','Course, Assignment, Completion','Find courses;View assigned training;View completion records','Assign learning by role;Track completions;Track expirations'),
('TM-05','Compensation planning','manager, compensation admin',5,'compensation','Budget, Proposal','Propose salaries within displayed budgets;Propose bonuses within displayed budgets','Validate eligibility;Validate budgets;Transfer approved changes to payroll'),
('TM-06','Succession','talent admin',5,'succession','TalentProfile, SuccessionPlan','Review talent profiles;Nominate successors','Maintain succession plans;Maintain readiness assessments'),
('TM-07','Workforce planning','workforce planner',5,'workforce','Scenario, WorkforceCost','Compare headcount scenarios;Compare workforce-cost scenarios','Aggregate workforce data;Calculate scenario costs'),
('ERP-01','Finance','accountant, controller',6,'finance','Journal, Ledger, Period','Enter journals;Review receivables;Review payables;Review financial statements','Validate postings;Maintain ledgers;Execute period close'),
('ERP-02','Procurement','buyer, receiver, approver',6,'procurement','Requisition, PO, Receipt, Invoice','Create requisitions;Create purchase orders;Confirm receipts','Route approvals;Match orders receipts and invoices'),
('ERP-03','Inventory','warehouse operator',6,'inventory','StockMovement, Valuation','View stock;Record receipts;Record issues;Record transfers;Record counts','Maintain balances;Maintain valuation;Apply replenishment rules'),
('ERP-04','Sales','sales, credit controller',6,'sales','Quote, Order, Delivery, Invoice, Return','Manage quotations;Manage orders;Manage deliveries;Manage invoices;Manage returns','Apply pricing;Check availability;Apply credit rules;Apply billing rules'),
('ERP-05','Manufacturing','production planner',6,'manufacturing','ProductionOrder, BOM, Capacity','Review production orders;Record output;Record material consumption','Calculate material requirements;Calculate capacity needs;Calculate production costs'),
('ERP-06','Assets','asset manager',6,'assets','Asset, Depreciation, Disposal','Register assets;Record transfers;Record disposals','Calculate depreciation;Post asset transactions'),
('ERP-07','Projects','project manager',6,'projects','Project, Budget, Expense','Maintain plans;Maintain budgets;Maintain time;Maintain expenses','Allocate costs;Calculate budget variances'),
('SH-01','Sign-in and access','all, security admin',1,'sign-in','Identity, Membership, Policy','Provide sign-in;Display role-appropriate actions','Authenticate users;Enforce every request permission;Enforce record access;Enforce field access'),
('SH-02','Approval inbox','requester, approver',1,'approvals','ApprovalTask, Decision, Delegation','Show pending tasks;Approve;Reject;Comment;Delegate','Resolve approvers;Enforce approval order;Record decisions'),
('SH-03','Notifications','all',1,'notifications','Notification, DeliveryAttempt','Display reminders;Display status changes;Display overdue tasks','Trigger from business events;Track delivery outcomes'),
('SH-04','Reporting','authorised analyst',7,'reports','Projection, ExportJob','Filter dashboards;Drill into authorised records;Export reports','Calculate agreed metrics;Restrict reports;Restrict exports'),
('SH-05','Audit history','authorised auditor',1,'people/history','AuditEvent','Inspect changes;Inspect approval history','Record actor;Record timestamp;Record old values;Record new values;Record reason'),
('SH-06','Configuration','configuration admin',2,'settings','Calendar, Rule, Template, Reference','Maintain calendars;Maintain rules;Maintain templates;Maintain reference data','Validate configuration;Control effective dates'),
('SH-07','Data migration','migration admin',7,'migration','ImportJob, Mapping, Reconciliation','Upload templates;Review validation errors before import','Validate imports;Map records;Deduplicate;Reconcile imported records'),
('SH-08','Integration monitoring','integration operator',7,'integrations','OutboxEvent, Delivery, Replay','Show sync status;Allow authorised retries','Exchange connected system data;Handle failures;Prevent duplicates'),
('SH-09','Localisation','all, configuration admin',2,'settings/localisation','Locale, LegalEntity, Currency','Display languages;Display currencies;Display dates;Display country-specific fields','Apply country rules;Apply legal-entity rules;Apply currency rules;Apply calendar rules'),
('SH-10','Privacy administration','employee, privacy officer',7,'privacy','PrivacyRequest, Retention, LegalHold','Submit permitted access requests;Manage access requests;Submit permitted deletion requests;Manage deletion requests','Apply retention;Apply legal hold;Apply anonymisation;Apply deletion policies'),
]
assert len(rows)==45
assert {p:sum(r[0].startswith(p+'-') for r in rows) for p in ['HR','TS','DS','PY','TM','ERP','SH']}=={'HR':6,'TS':4,'DS':2,'PY':9,'TM':7,'ERP':7,'SH':10}
out=[]
for id,name,role,phase,screen,model,front,back in rows:
 criteria=[]
 for side,items in [('frontend',front),('backend',back)]:
  for index,behavior in enumerate(items.split(';'),1):
   criteria.append({'id':f'{id}-{side[0].upper()}{index:02}','sourceBehaviour':behavior,'acceptance':f'Given an authorised {role} and valid scoped records, {behavior.lower()} through the {screen} workflow; verify persisted outcome or authorised view. Reject invalid and unauthorised input without mutation; preserve entered values and provide recovery.','implementation':'not implemented','verification':'not run'})
 criteria.extend([{'id':f'{id}-D01','derived':True,'acceptance':'Tenant/record/field permissions enforced on direct API calls, including altered identifiers; no private data in errors.','implementation':'not implemented','verification':'not run'},{'id':f'{id}-D02','derived':True,'acceptance':'Mutations retain actor, UTC recording instant, business effective date where applicable, reason and correlation; safe duplicate/concurrent command outcome.','implementation':'not implemented','verification':'not run'}])
 out.append({'id':id,'name':name,'roles':role,'workflow':name,'phase':phase,'screen':'/'+screen,'api':'/api/v1/'+screen,'useCase':name.replace(' ','')+'UseCases (planned)' if phase>1 else 'PeopleUseCases (partial)','domainModel':model,'acceptanceTests':[c['id'] for c in criteria],'frontend':'not implemented','backend':'not implemented','verification':'not run','reason':f'Scheduled Phase {phase}; all listed behaviours remain in scope.','criteria':criteria})
# Preserve reviewed implementation evidence when regenerating the source inventory.
existing_path=Path('docs/traceability.json')
if existing_path.exists():
 prior={r['id']:r for r in json.loads(existing_path.read_text())}
 for row in out:
  old=prior.get(row['id'],{})
  # Preserve reviewed metadata, including new evidence fields, while regenerating source fields.
  source_fields={'id','name','roles','workflow','phase','acceptanceTests','criteria'}
  for key,value in old.items():
   if key not in source_fields:row[key]=value
  old_criteria={c['id']:c for c in old.get('criteria',[])}
  generated_ids={c['id'] for c in row['criteria']}
  for criterion in old.get('criteria',[]):
   if criterion['id'] not in generated_ids:
    if not criterion.get('derived') or not criterion['id'].startswith(row['id']+'-D'):
     raise ValueError('Unrecognised source criterion: '+criterion['id'])
    row['criteria'].append(criterion)
  row['acceptanceTests']=[c['id'] for c in row['criteria']]
  for criterion in row['criteria']:
   old_c=old_criteria.get(criterion['id'],{})
   for key,value in old_c.items():
    if key not in {'id','sourceBehaviour','derived','acceptance'}:criterion[key]=value
Path('docs/traceability.json').write_text(json.dumps(out,indent=2)+'\n')
header='# Requirement traceability\n\nAuthoritative source inventory: 45 (HR 6, TS 4, DS 2, PY 9, TM 7, ERP 7, SH 10). Behaviour-level acceptance and separate implementation/verification status: [traceability.json](traceability.json). Derived criteria are marked. Implemented subsets and planned contracts are distinguished by row status and scoped evidence.\n\n| ID | Workflow / roles | Phase | Screen / API resource | Model | Frontend | Backend | Verification |\n|---|---|---|---|---|---|---|---|\n'
for r in out:header+=f"| {r['id']} | {r['name']} / {r['roles']} | {r['phase']} | `{r['screen']}` / `{r['api']}` | {r['domainModel']} | {r['frontend']} | {r['backend']} | {r['verification']} |\n"
Path('docs/traceability.md').write_text(header)
print(f'Validated {len(out)} source requirements and {sum(len(r["criteria"]) for r in out)} acceptance criteria.')
