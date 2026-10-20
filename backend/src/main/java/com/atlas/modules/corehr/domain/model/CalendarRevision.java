package com.atlas.modules.corehr.domain.model;

import java.time.*;
import java.util.*;

public record CalendarRevision(int version, LocalDate effectiveDate, WorkCalendar policy) {
  public CalendarRevision {
    if (version < 1 || version > 20)
      throw new ChangeRequest.Validation(
          Map.of("version", "Calendar amendments are limited to twenty."));
    if (policy == null
        || effectiveDate == null
        || effectiveDate.isBefore(policy.effectiveFrom())
        || effectiveDate.isAfter(policy.effectiveUntil()))
      throw new ChangeRequest.Validation(
          Map.of("effectiveDate", "Choose a date within the calendar validity period."));
    if (policy.closedDates().stream().anyMatch(d -> d.isBefore(effectiveDate)))
      throw new ChangeRequest.Validation(
          Map.of("closedDates", "Amended closures must be on/after the amendment date."));
  }

  public void validateNew(CalendarRevision previous, LocalDate today) {
    if (effectiveDate.isBefore(today) || effectiveDate.isAfter(today.plusYears(1)))
      throw new ChangeRequest.Validation(
          Map.of("effectiveDate", "Use today or a date within the next year."));
    if (version != (previous == null ? 1 : previous.version() + 1))
      throw new ChangeRequest.Conflict(
          "Calendar changed. Reopen its latest context before amending.");
    if (previous != null && !effectiveDate.isAfter(previous.effectiveDate()))
      throw new ChangeRequest.Validation(
          Map.of("effectiveDate", "Use a date strictly later than the last amendment."));
  }

  public record Selection(WorkCalendar policy, int version, LocalDate effectiveDate) {}

  public static Selection select(
      WorkCalendar base, List<CalendarRevision> revisions, LocalDate date) {
    Objects.requireNonNull(date);
    return revisions.stream()
        .filter(r -> !r.effectiveDate().isAfter(date))
        .max(Comparator.comparingInt(CalendarRevision::version))
        .map(r -> new Selection(r.policy(), r.version(), r.effectiveDate()))
        .orElseGet(() -> new Selection(base, 0, base.effectiveFrom()));
  }
}
