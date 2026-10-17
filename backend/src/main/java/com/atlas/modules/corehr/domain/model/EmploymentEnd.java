package com.atlas.modules.corehr.domain.model;

import java.time.*;
import java.util.*;

public record EmploymentEnd(
    String requestId,
    String employeeId,
    LocalDate lastWorkingDate,
    LocalDate inactiveDate,
    String actor,
    String actorEmployeeId,
    String reason,
    long inputVersion,
    long newVersion,
    String oldStatus,
    String newStatus,
    Instant recordedAt,
    String correlationId) {
  public static LocalDate inactiveDate(LocalDate lastWorkingDate) {
    return lastWorkingDate.plusDays(1);
  }

  public static void requireEligible(Actor actor, Employee employee, LocalDate inactiveDate) {
    if (!actor.hr() || actor.employeeId().equals(employee.id())) throw new ChangeRequest.Denied();
    if (!Set.of("Active", "On leave").contains(employee.status())
        || !inactiveDate.isAfter(employee.startDate()))
      throw new ChangeRequest.Conflict(
          "Only active/on-leave employment can end after employment starts.");
  }

  public boolean effectiveOn(LocalDate date) {
    return !inactiveDate.isAfter(date);
  }
}
