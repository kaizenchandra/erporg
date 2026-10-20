export type CompanyCalendar = {
  code: string;
  name: string;
  timeZone: string;
  effectiveFrom: string;
  effectiveUntil: string;
  workingDays: number[];
  closedDates: string[];
};
export type CalendarEntry = {
  calendar: CompanyCalendar;
  createdBy: string;
  reason: string;
  recordedAt: string;
  correlationId: string;
};
export type CalendarPage = {
  retirementDates: Record<string, string>;
  items: CalendarEntry[];
  total: number;
  page: number;
  size: number;
};
export type CalendarPreview = {
  code: string;
  date: string;
  timeZone: string;
  classification: "WORKING_DAY" | "NON_WORKING_WEEKDAY" | "COMPANY_CLOSURE";
  working: boolean;
  revision: number;
  policyEffectiveFrom: string;
};
export const weekdays = [
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
  "Sunday",
];
