export type ExitTask = {
  id: string;
  requestId: string;
  employeeId: string;
  assigneeId: string;
  ordinal: number;
  title: string;
  instructions: string;
  dueDate: string;
  reason: string;
  createdBy: string;
  recordedAt: string;
  status: "OPEN" | "COMPLETED" | "CANCELLED";
  version: number;
  completedBy: string | null;
  completionComment: string | null;
  completedAt: string | null;
};
export type ExitTaskItem = { task: ExitTask; overdue: boolean };
export type ExitTaskCase = {
  items: ExitTaskItem[];
  version: number;
  asOf: string;
};
export type ExitTaskPage = {
  items: ExitTaskItem[];
  total: number;
  page: number;
  size: number;
  asOf: string;
};
export type ExitTaskAudit = {
  oldStatus: string | null;
  newStatus: string;
  actor: string;
  reason: string;
  effectiveDate: string;
  recordedAt: string;
  correlationId: string;
};

export type ExitTaskManagementEvent = {
  taskId: string;
  ordinal: number;
  action: "REASSIGN" | "CANCEL";
  oldAssigneeId: string;
  newAssigneeId: string;
  oldStatus: "OPEN";
  newStatus: "OPEN" | "CANCELLED";
  actor: string;
  actorEmployeeId: string;
  reason: string;
  inputVersion: number;
  recordedAt: string;
  correlationId: string;
};
