package com.atlas.modules.corehr.domain.model;

import java.time.*;
import java.util.*;

public record Resignation(
    String id,
    String employeeId,
    LocalDate lastWorkingDate,
    String reason,
    String status,
    String requestedBy,
    long employeeVersion,
    Instant recordedAt,
    long version,
    String decidedBy,
    String reviewerEmployeeId,
    String comment,
    Instant decidedAt) {
  public static void validate(LocalDate date, String reason) {
    var errors = new LinkedHashMap<String, String>();
    if (date == null) errors.put("lastWorkingDate", "Proposed last working date is required.");
    if (reason == null || reason.isBlank() || reason.length() > 500)
      errors.put("reason", "A reason of 1–500 characters is required.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
  }

  public static void eligible(Employee employee, LocalDate date, LocalDate today) {
    if (!Set.of("Active", "On leave").contains(employee.status()))
      throw new ChangeRequest.Conflict(
          "Only active or on-leave employees may request resignation.");
    if (date.isBefore(today)
        || date.isAfter(today.plusYears(1))
        || date.isBefore(employee.startDate()))
      throw new ChangeRequest.Validation(
          Map.of(
              "lastWorkingDate",
              "Choose today or a date within the next year, on or after employment starts."));
  }

  public void ensureReview(Actor actor, String decision, long expected) {
    if (!actor.hr() || actor.subject().equals(requestedBy) || actor.employeeId().equals(employeeId))
      throw new ChangeRequest.Denied();
    if (!Set.of("APPROVED", "REJECTED").contains(decision == null ? "" : decision))
      throw new IllegalArgumentException("Choose approve or reject.");
    if (!status.equals("SUBMITTED") || version != expected)
      throw new ChangeRequest.Conflict("The request changed. Refresh the resignation list.");
  }
}
