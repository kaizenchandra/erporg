package com.atlas.modules.corehr.domain.model;

import java.time.*;
import java.util.*;

/** Company reference assignment, not statutory entitlement or pay. */
public final class CalendarAssignment {
  private CalendarAssignment() {}

  public static void shape(String code) {
    if (code == null || (!code.isEmpty() && !code.matches("[A-Z0-9-]{2,30}")))
      throw new ChangeRequest.Validation(
          Map.of("newValue", "Choose a canonical company calendar code or explicit unassignment."));
  }

  public static void validate(
      String code,
      LocalDate date,
      LocalDate start,
      LocalDate today,
      WorkCalendar calendar,
      boolean retirementRecorded) {
    shape(code);
    ChangeRequest.validateDate(date, today);
    if (date.isBefore(start))
      throw new ChangeRequest.Validation(
          Map.of("effectiveDate", "Calendar assignment cannot precede this employment episode."));
    if (code.isEmpty()) return;
    if (calendar == null || !calendar.code().equals(code))
      throw new ChangeRequest.Validation(
          Map.of("newValue", "Choose a company calendar in this organisation."));
    if (retirementRecorded)
      throw new ChangeRequest.Conflict(
          "Calendar retirement is recorded. Choose another calendar or reject and resubmit.");
    if (date.isBefore(calendar.effectiveFrom()) || date.isAfter(calendar.effectiveUntil()))
      throw new ChangeRequest.Validation(
          Map.of("effectiveDate", "Assignment date must be inside calendar validity."));
  }
}
