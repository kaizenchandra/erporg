export type Kind =
  | "requisitions"
  | "onboarding"
  | "reviews"
  | "courses"
  | "learning"
  | "compensation"
  | "succession"
  | "scenarios";
export type Meta = {
  id: string;
  version: number;
  status: string;
  preparedBy: string;
  preparedEmployee: string;
  recordedAt: string;
};
export type Worker = {
  id: string;
  name: string;
  title: string;
  department: string;
  managerId: string;
  status: string;
  start: string;
  version: number;
};
export type Context = {
  actor: { subject: string; tenant: string; employeeId: string; role: string };
  workers: { items: Worker[]; total: number };
};
export type Candidate = {
  id: string;
  name: string;
  email: string;
  phone: string;
  stage: string;
  interviewAt: string;
  interviewNotes: string;
  offer: string;
  hireId: string;
  employeeId: string;
};
export type Requisition = {
  meta: Meta;
  title: string;
  department: string;
  location: string;
  managerId: string;
  start: string;
  candidates: Candidate[];
};
export type Task = {
  id: string;
  title: string;
  assignee: string;
  dependsOn: string[];
  complete: boolean;
  completedBy: string;
  completedAt: string | null;
  evidence: string;
};
export type Onboarding = {
  meta: Meta;
  hireId: string;
  employeeId: string;
  joiningForm: string;
  tasks: Task[];
};
export type Review = {
  meta: Meta;
  employeeId: string;
  cycle: string;
  from: string;
  through: string;
  minimumRating: number;
  maximumRating: number;
  goals: { id: string; text: string; feedback: string }[];
  selfReview: string;
  rating: number | null;
  decisionComment: string;
};
export type Course = {
  meta: Meta;
  title: string;
  description: string;
  role: string;
  validDays: number;
};
export type Enrollment = {
  meta: Meta;
  courseId: string;
  employeeId: string;
  courseTitle: string;
  validDays: number;
  due: string;
  completed: string | null;
  expires: string | null;
  evidence: string;
};
export type Proposal = {
  employeeId: string;
  currentSalary: string;
  proposedSalary: string;
  bonus: string;
  effectiveDate: string;
};
export type Compensation = {
  meta: Meta;
  title: string;
  currency: string;
  budget: string;
  proposals: Proposal[];
  total: string;
  payrollIntakeId: string;
};
export type Succession = {
  meta: Meta;
  position: string;
  nominees: {
    employeeId: string;
    skills: string;
    readiness: string;
    assessment: string;
  }[];
};
export type Scenario = {
  meta: Meta;
  title: string;
  currency: string;
  asOf: string;
  workerSnapshot: string[];
  lines: {
    dimension: string;
    baselineHeadcount: number;
    proposedHeadcount: number;
    annualUnitCost: string;
  }[];
  baselineHeadcount: number;
  proposedHeadcount: number;
  baselineCost: string;
  proposedCost: string;
  variance: string;
};
export type TalentRecord =
  | Requisition
  | Onboarding
  | Review
  | Course
  | Enrollment
  | Compensation
  | Succession
  | Scenario;
export type RecordPage = {
  items: TalentRecord[];
  total: number;
  page: number;
  size: number;
};
export type Audit = {
  id: string;
  actor: string;
  action: string;
  reason: string;
  recordedAt: string;
  before: string;
  after: string;
  correlationId: string;
};
export type Intake = {
  id: string;
  sourceId: string;
  currency: string;
  status: string;
  receivedBy: string;
  recordedAt: string;
  awards: Proposal[];
};
export const labels: Record<Kind, string> = {
  requisitions: "Recruitment",
  onboarding: "Onboarding",
  reviews: "Performance",
  courses: "Course catalogue",
  learning: "Assigned learning",
  compensation: "Compensation",
  succession: "Succession",
  scenarios: "Workforce planning",
};
export function title(r: TalentRecord): string {
  return "title" in r
    ? r.title
    : "position" in r
      ? r.position
      : "cycle" in r
        ? `${r.cycle} · ${r.employeeId}`
        : "courseTitle" in r
          ? `${r.courseTitle} · ${r.employeeId}`
          : `Joining plan · ${r.employeeId}`;
}
