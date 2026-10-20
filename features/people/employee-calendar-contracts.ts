import type {
  CalendarPreview,
  CompanyCalendar,
} from "@/features/settings/calendar-contracts";
import type { CalendarRevision } from "@/features/settings/calendar-amendment-contracts";
export type EmployeeCalendarResult = {
  employeeId: string;
  calendarName: string;
  preview: CalendarPreview;
};
export type CalendarAssignmentReview = {
  requestId: string;
  code: string;
  expectedPolicyVersion: number | null;
  currentPolicyVersion: number;
  policyChanged: boolean;
  retirementRecorded: boolean;
  base: CompanyCalendar | null;
  history: CalendarRevision[];
};
export type EmployeeCalendarHistoryResult = {
  employeeId: string;
  asOf: string;
  currentEpisodeStart: string;
  items: {
    versionId: string;
    episodeStart: string;
    effectiveDate: string;
    recordedAt: string;
    calendarCode: string;
    calendarName: string;
    timeZone: string;
  }[];
  total: number;
  page: number;
  size: number;
};
