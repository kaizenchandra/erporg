package com.atlas.modules.corehr.domain.model;

import java.time.Instant;

public record HireRequest(
    String id,
    String name,
    String email,
    String phone,
    String title,
    String department,
    String location,
    String managerId,
    java.time.LocalDate startDate,
    String reason,
    String status,
    String requestedBy,
    String requesterEmployeeId,
    String employeeId,
    Instant recordedAt,
    String decidedBy,
    String comment) {
  public HireDetails details() {
    return new HireDetails(name, email, phone, title, department, location, managerId, startDate);
  }

  public void ensureDecision(Actor actor, String decision) {
    if (!actor.hr()
        || actor.subject().equals(requestedBy)
        || actor.employeeId().equals(requesterEmployeeId)) throw new ChangeRequest.Denied();
    if (!java.util.Set.of("APPROVED", "REJECTED").contains(decision == null ? "" : decision))
      throw new IllegalArgumentException("Choose approve or reject.");
    if (!status.equals("SUBMITTED"))
      throw new ChangeRequest.Conflict("This hire has already been decided.");
  }
}
