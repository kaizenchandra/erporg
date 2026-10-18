export type Revocation = {
  activationId: string;
  employeeId: string;
  subject: string;
  inputVersion: number;
  newVersion: number;
  actor: string;
  actorEmployeeId: string;
  reason: string;
  recordedAt: string;
  correlationId: string;
};
