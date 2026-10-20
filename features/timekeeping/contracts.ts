export type Shift = {
  id: string;
  employeeId: string;
  episodeStart: string;
  start: string;
  end: string | null;
  zone: string;
  version: number;
  reason: string;
  recordedAt: string;
};
export type Entry = { date: string; minutes: number; code: string };
export type Sheet = {
  id: string;
  employeeId: string;
  episodeStart: string;
  week: string;
  zone: string;
  status:
    | "DRAFT"
    | "SUBMITTED"
    | "APPROVED"
    | "REJECTED"
    | "RETURNED"
    | "CORRECTED";
  version: number;
  entries: Entry[];
  totalMinutes: number;
  reason: string;
  decidedBy: string | null;
  comment: string | null;
};
export type Allocation = {
  sheetId: string;
  date: string;
  minutes: number;
  code: string;
  postedAt: string;
};
export type SheetContext = {
  sheet: Sheet;
  allocations: Allocation[];
  allocatedMinutes: number;
};
export type Audit = {
  id: string;
  entityId: string;
  action: string;
  actor: string;
  reason: string;
  before: string;
  after: string;
  recordedAt: string;
  correlationId: string;
};
export type Page<T> = { items: T[]; total: number; page: number; size: number };
export const hours = (minutes: number) =>
  `${Math.floor(minutes / 60)}h ${minutes % 60}m`;

/** Presentation only: keep the original instant and microseconds when editing in its IANA zone. */
export function zonedTimestamp(value: string, zone: string) {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: zone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
    timeZoneName: "longOffset",
    calendar: "iso8601",
    numberingSystem: "latn",
  }).formatToParts(new Date(value));
  const part = (name: Intl.DateTimeFormatPartTypes) =>
    parts.find((p) => p.type === name)?.value ?? "";
  const offset = part("timeZoneName").replace("GMT", "") || "+00:00";
  const fraction = value.match(/\.([0-9]+)Z$/)?.[1];
  return `${part("year")}-${part("month")}-${part("day")}T${part("hour")}:${part("minute")}:${part("second")}${fraction ? `.${fraction}` : ""}${offset}`;
}
