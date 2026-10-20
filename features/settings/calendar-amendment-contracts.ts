import type { CalendarRetirementEntry } from "./calendar-retirement-contracts";
import type { CompanyCalendar, CalendarEntry } from "./calendar-contracts";
export type CalendarRevision = {
  version: number;
  effectiveDate: string;
  policy: CompanyCalendar;
};
export type CalendarRevisionEntry = {
  revision: CalendarRevision;
  createdBy: string;
  reason: string;
  recordedAt: string;
  correlationId: string;
};
export type CalendarAmendmentContext = {
  assignmentRecorded: boolean;
  retirement: CalendarRetirementEntry | null;
  retirementStatus: "NONE" | "SCHEDULED" | "RETIRED";
  retirementDraft: string | null;
  base: CalendarEntry;
  currentPolicy: CompanyCalendar;
  currentVersion: number;
  currentEffectiveDate: string;
  version: number;
  history: CalendarRevisionEntry[];
  draft: {
    effectiveDate: string;
    workingDays: number[];
    closedDates: string[];
  } | null;
};
