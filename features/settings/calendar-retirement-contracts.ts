export type CalendarRetirementEntry = {
  retirement: { code: string; version: number; effectiveDate: string };
  createdBy: string;
  reason: string;
  recordedAt: string;
  correlationId: string;
};
