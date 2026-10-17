export type Resignation = {
  id: string;
  employeeId: string;
  lastWorkingDate: string;
  reason: string;
  status: "SUBMITTED" | "APPROVED" | "REJECTED";
  requestedBy: string;
  employeeVersion: number;
  recordedAt: string;
  version: number;
  decidedBy: string | null;
  reviewerEmployeeId: string | null;
  comment: string | null;
  decidedAt: string | null;
};
export type ResignationPage = {
  items: Resignation[];
  total: number;
  page: number;
  size: number;
};
export type ResignationAudit = {
  oldStatus: string | null;
  newStatus: string;
  actor: string;
  reason: string;
  effectiveDate: string;
  recordedAt: string;
  correlationId: string;
};
