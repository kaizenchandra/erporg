package com.atlas.modules.corehr.domain.model;

import java.time.*;

public record Rehire(
    String id,
    String employeeId,
    LocalDate startDate,
    String title,
    String department,
    String location,
    String managerId,
    LocalDate priorInactiveDate,
    String actor,
    String actorEmployeeId,
    String reason,
    long inputVersion,
    Instant recordedAt,
    String correlationId,
    String status,
    String reviewer,
    String reviewerEmployeeId,
    String comment,
    Long reviewVersion,
    Instant reviewedAt,
    String reviewCorrelationId) {
  public RehireDetails details() {
    return new RehireDetails(startDate, title, department, location, managerId);
  }

  public static void requireEnded(Actor actor, Employee employee) {
    if (!actor.hr() || actor.employeeId().equals(employee.id())) throw new ChangeRequest.Denied();
    if (!employee.status().equals("Ended"))
      throw new ChangeRequest.Conflict(
          "Only an employee whose current employment has ended may be rehired.");
  }
}
