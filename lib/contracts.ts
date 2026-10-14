export type Actor = {
  subject: string;
  tenant: string;
  employeeId: string;
  name: string;
  role: "HR" | "MANAGER" | "EMPLOYEE";
};
export type Employee = {
  id: string;
  name: string;
  email: string;
  phone: string;
  address: string | null;
  emergencyContactName: string | null;
  emergencyContactRelationship: string | null;
  emergencyContactPhone: string | null;
  title: string;
  department: string;
  location: string;
  status: string;
  managerId: string | null;
  startDate: string;
  version: number;
};
export type ChangeRequest = {
  id: string;
  employeeId: string;
  employeeName: string;
  field: string;
  oldValue: string;
  newValue: string;
  effectiveDate: string;
  reason: string;
  status: string;
  requestedBy: string;
  employeeVersion: number;
  recordedAt: string;
  decidedBy: string | null;
  comment: string | null;
};
export type AuditEvent = {
  action: string;
  field: string;
  oldValue: string;
  newValue: string;
  actor: string;
  reason: string;
  recordedAt: string;
  correlationId: string;
  effectiveDate: string;
};
export type Notification = { id: number; message: string; recordedAt: string };

export type PeopleSummary = {
  total: number;
  active: number;
  onLeave: number;
  pending: number;
};
