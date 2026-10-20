package com.atlas.modules.corehr.domain.model;

import java.time.*;
import java.util.*;

/** Terminal intent for an unassigned company calendar; not employee or payroll policy. */
public record CalendarRetirement(String code, int version, LocalDate effectiveDate) {
  public CalendarRetirement {
    if (code == null || !code.matches("[A-Z0-9-]{2,30}"))
      throw new ChangeRequest.Validation(Map.of("code", "Choose a valid calendar code."));
    if (version < 0 || version > 20)
      throw new ChangeRequest.Validation(
          Map.of("version", "Use the exact current calendar version."));
    if (effectiveDate == null || effectiveDate.getYear() < 1 || effectiveDate.getYear() > 9999)
      throw new ChangeRequest.Validation(Map.of("effectiveDate", "Enter a valid retirement date."));
  }

  public void validateNew(WorkCalendar base, CalendarRevision last, LocalDate today) {
    if (!code.equals(base.code())) throw new ChangeRequest.Denied();
    if (version != (last == null ? 0 : last.version()))
      throw new ChangeRequest.Conflict(
          "Calendar changed. Reopen its latest context before retiring.");
    if (effectiveDate.isBefore(today)
        || effectiveDate.isAfter(today.plusYears(1))
        || effectiveDate.isBefore(base.effectiveFrom())
        || effectiveDate.isAfter(base.effectiveUntil()))
      throw new ChangeRequest.Validation(
          Map.of(
              "effectiveDate", "Retirement must be today–one year and within calendar validity."));
    if (last != null && !effectiveDate.isAfter(last.effectiveDate()))
      throw new ChangeRequest.Validation(
          Map.of("effectiveDate", "Retirement must be strictly after the latest amendment."));
  }

  public boolean applies(LocalDate date) {
    return !date.isBefore(effectiveDate);
  }
}
