export type DocumentTemplate = {
  code: string;
  title: string;
  body: string;
  required: boolean;
  effectiveDate: string;
  actor: string;
  reason: string;
  recordedAt: string;
  correlationId: string;
  version: number;
  retired: boolean;
};
export type DocumentEvidence = {
  code: string;
  employeeId: string;
  rootId: string;
  linkedVersion: number;
  actor: string;
  reason: string;
  recordedAt: string;
  correlationId: string;
  classificationVersion: number;
  previousRootId: string | null;
};
export type DocumentCompleteness = {
  employeeId: string;
  asOf: string;
  items: {
    code: string;
    title: string;
    required: boolean;
    effectiveDate: string;
    state: string;
    rootId: string | null;
    documentId: string | null;
    version: number | null;
    scanStatus: string | null;
  }[];
};
