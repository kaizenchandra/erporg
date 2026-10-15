export type RetentionEvent = {
  rootId: string;
  version: number;
  action: string;
  retainUntil: string | null;
  basis: string | null;
  holdId: string | null;
  caseReference: string | null;
  actor: string;
  actorEmployeeId: string;
  reason: string;
  recordedAt: string;
  correlationId: string;
};
export type RetentionReview = {
  rootId: string;
  employeeId: string;
  version: number;
  asOf: string;
  state: string;
  retainUntil: string | null;
  basis: string | null;
  holds: {
    id: string;
    caseReference: string;
    placedBy: string;
    placedEmployeeId: string;
    active: boolean;
  }[];
  history: RetentionEvent[];
  deletionAvailable: false;
};
