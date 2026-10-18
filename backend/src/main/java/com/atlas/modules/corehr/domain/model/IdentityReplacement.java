package com.atlas.modules.corehr.domain.model;

import java.time.Instant;

public record IdentityReplacement(
    String id,
    String employeeId,
    String predecessorId,
    String subject,
    String reason,
    long inputVersion,
    String actor,
    String actorEmployeeId,
    Instant recordedAt,
    String correlationId,
    String status,
    String reviewer,
    String reviewerEmployeeId,
    String comment,
    Long reviewVersion,
    Instant reviewedAt,
    String reviewCorrelationId) {
  public IdentityReplacement {
    if (actorEmployeeId.equals(employeeId)) throw new ChangeRequest.Denied();
    if (inputVersion < 0 || reason == null || reason.isBlank() || reason.length() > 500)
      throw new IllegalArgumentException("Invalid replacement request.");
    if (!java.util.Set.of("SUBMITTED", "APPROVED", "REJECTED").contains(status))
      throw new IllegalArgumentException("Invalid replacement state.");
    if (!status.equals("SUBMITTED")
        && (reviewer == null
            || reviewerEmployeeId == null
            || comment == null
            || comment.isBlank()
            || comment.length() > 500
            || reviewVersion == null
            || reviewVersion < 0
            || reviewedAt == null
            || reviewedAt.isBefore(recordedAt)))
      throw new IllegalArgumentException("Reviewed replacement needs valid decision evidence.");
    if (status.equals("APPROVED") && reviewVersion != inputVersion)
      throw new ChangeRequest.Conflict("Approval snapshot changed.");
    if (!status.equals("SUBMITTED")
        && (reviewer.equals(actor)
            || reviewerEmployeeId.equals(actorEmployeeId)
            || reviewerEmployeeId.equals(employeeId))) throw new ChangeRequest.Denied();
  }

  public IdentityReplacement decide(
      String who,
      String whoEmployee,
      String decision,
      String decisionReason,
      long version,
      Instant at,
      String correlation) {
    if (!status.equals("SUBMITTED"))
      throw new ChangeRequest.Conflict("Replacement was already reviewed.");
    if (!java.util.Set.of("APPROVED", "REJECTED").contains(decision))
      throw new IllegalArgumentException("Choose APPROVED or REJECTED.");
    return new IdentityReplacement(
        id,
        employeeId,
        predecessorId,
        subject,
        reason,
        inputVersion,
        actor,
        actorEmployeeId,
        recordedAt,
        correlationId,
        decision,
        who,
        whoEmployee,
        decisionReason,
        version,
        at,
        correlation);
  }
}
