export type LegalEntity = {
  code: string;
  name: string;
  countryCode: string;
  currencyCode: string;
  timeZone: string;
  effectiveFrom: string;
};
export type LegalEntityEntry = {
  entity: LegalEntity;
  createdBy: string;
  reason: string;
  recordedAt: string;
  correlationId: string;
};
export type LegalEntityPage = {
  retirementDates: Record<string, string>;
  items: LegalEntityEntry[];
  total: number;
  page: number;
  size: number;
};
export type LegalEntityOptions = {
  countries: { code: string; name: string }[];
  currencies: { code: string; name: string }[];
  timeZones: string[];
};
export type RetirementProposal = {
  id: string;
  code: string;
  ordinal: number;
  sourceVersion: number;
  effectiveDate: string;
  reason: string;
  requestedBy: string;
  requesterEmployeeId: string;
  recordedAt: string;
  correlationId: string;
};
export type RetirementDecision = {
  requestId: string;
  decision: "APPROVED" | "REJECTED";
  comment: string;
  actor: string;
  actorEmployeeId: string;
  recordedAt: string;
  correlationId: string;
};
export type RetirementState = {
  request: RetirementProposal;
  decision: RetirementDecision | null;
};
export type RetirementContext = {
  reference: LegalEntityEntry;
  version: number;
  retirementDate: string | null;
  assignmentRecorded: boolean;
  requests: RetirementState[];
};
