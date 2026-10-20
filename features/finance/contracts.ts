export type Grant = { entity: string; permission: string };
export type Account = {
  code: string;
  name: string;
  kind: string;
  requiresCostCentre: boolean;
};
export type Ledger = {
  entity: string;
  currency: string;
  effectiveFrom: string;
  costCentres: string[];
  accounts: Account[];
};
export type Context = { grants: Grant[]; ledgers: Ledger[] };
export type Line = {
  account: string;
  costCentre: string;
  debit: string;
  credit: string;
  memo: string;
};
export type Journal = {
  id: string;
  entity: string;
  currency: string;
  postingDate: string;
  sourceType: string;
  sourceId: string;
  reversalOf: string | null;
  status: string;
  version: number;
  preparedBy: string;
  preparedEmployee: string;
  approvedBy: string | null;
  approvedEmployee: string | null;
  lines: Line[];
  reason: string;
  recordedAt: string;
};
export type Page<T> = { items: T[]; total: number; page: number; size: number };
export type Period = {
  month: string;
  closed: boolean;
  version: number;
  reason: string;
  actor: string;
  recordedAt: string;
};
export type Audit = {
  id: string;
  documentId: string;
  action: string;
  actor: string;
  reason: string;
  before: string;
  after: string;
  recordedAt: string;
  correlationId: string;
};
export type TrialBalance = {
  entity: string;
  currency: string;
  from: string;
  through: string;
  items: {
    account: string;
    costCentre: string;
    debit: string;
    credit: string;
  }[];
  debit: string;
  credit: string;
};
/** Financial APIs return plain decimals; format cents without converting them to Number. */
export const amount = (value: string) => {
  const [whole, fraction = ""] = value.split(".");
  return `${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}.${fraction.padEnd(2, "0")}`;
};
