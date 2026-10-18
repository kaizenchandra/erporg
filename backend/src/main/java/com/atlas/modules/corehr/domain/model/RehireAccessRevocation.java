package com.atlas.modules.corehr.domain.model;

import java.time.Instant;

public record RehireAccessRevocation(
    String activationId,
    String employeeId,
    String subject,
    long inputVersion,
    long newVersion,
    String actor,
    String actorEmployeeId,
    String reason,
    Instant recordedAt,
    String correlationId) {
  public RehireAccessRevocation {
    if (inputVersion < 0 || newVersion != Math.addExact(inputVersion, 1))
      throw new IllegalArgumentException("Invalid access version transition.");
    if (actorEmployeeId.equals(employeeId)) throw new ChangeRequest.Denied();
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw new ChangeRequest.Validation(
          java.util.Map.of("reason", "A reason of 1–500 characters is required."));
  }
}
