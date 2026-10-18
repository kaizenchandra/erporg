package com.atlas.modules.corehr.domain.model;

import java.time.Instant;
import java.util.Map;

public record ResignationWithdrawal(
    String requestId,
    String employeeId,
    String actor,
    String reason,
    long inputVersion,
    long newVersion,
    Instant recordedAt,
    String correlationId) {
  public static ResignationWithdrawal withdraw(
      Resignation r, Actor actor, String reason, long version, Instant now, String correlation) {
    if (!actor.subject().equals(r.requestedBy()) || !actor.employeeId().equals(r.employeeId()))
      throw new ChangeRequest.Denied();
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw new ChangeRequest.Validation(
          Map.of("reason", "A withdrawal reason of 1–500 characters is required."));
    if (!r.status().equals("SUBMITTED") || r.version() != version)
      throw new ChangeRequest.Conflict(
          "Only the current pending resignation can be withdrawn. Refresh the list.");
    if (now.isBefore(r.recordedAt()))
      throw new ChangeRequest.Conflict("Withdrawal cannot precede submission.");
    return new ResignationWithdrawal(
        r.id(),
        r.employeeId(),
        actor.subject(),
        reason.trim(),
        version,
        version + 1,
        now,
        correlation);
  }
}
