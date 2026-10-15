export type EmployeeDocument = {
  id: string;
  employeeId: string;
  filename: string;
  mediaType: string;
  bytes: number;
  status: string;
  scanner: string;
  reason: string;
  recordedAt: string;
  scanAttempts: number;
  rootId: string;
  version: number;
};
export type DocumentPage = { items: EmployeeDocument[]; total: number };
export type DocumentAudit = {
  action: string;
  actor: string;
  recordedAt: string;
  reason: string;
};
