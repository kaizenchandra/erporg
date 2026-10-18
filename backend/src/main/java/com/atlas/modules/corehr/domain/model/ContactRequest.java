package com.atlas.modules.corehr.domain.model;

import java.time.*;
import java.util.*;

public record ContactRequest(
    String id,
    String employeeId,
    List<EmergencyContact> oldContacts,
    List<EmergencyContact> contacts,
    LocalDate effectiveDate,
    String reason,
    String status,
    String requestedBy,
    String requesterEmployeeId,
    long employeeVersion,
    Instant recordedAt,
    String correlationId,
    int version,
    String decidedBy,
    String comment,
    Instant decidedAt,
    String decisionCorrelationId) {
  public ContactRequest {
    oldContacts = EmergencyContact.snapshot(oldContacts);
    contacts = EmergencyContact.snapshot(contacts);
  }

  public void requireReviewer(Actor actor, String decision, int expected) {
    if (!actor.hr()
        || actor.subject().equals(requestedBy)
        || actor.employeeId().equals(employeeId)
        || actor.employeeId().equals(requesterEmployeeId)) throw new ChangeRequest.Denied();
    if (!Set.of("APPROVED", "REJECTED").contains(decision == null ? "" : decision))
      throw new ChangeRequest.Validation(Map.of("decision", "Choose approve or reject."));
    if (!status.equals("SUBMITTED") || version != expected)
      throw new ChangeRequest.Conflict("This contact request changed. Refresh its history.");
  }
}
