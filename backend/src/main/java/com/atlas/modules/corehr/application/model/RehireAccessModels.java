package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.RehireAccessRevocation;
import java.math.BigDecimal;

public final class RehireAccessModels {
  private RehireAccessModels() {}

  public record Revoke(
      String activationId, String reason, BigDecimal version, String idempotencyKey) {}

  public record Grant(
      String activationId,
      String employeeId,
      String subject,
      String role,
      int grantNumber,
      java.time.Instant recordedAt) {}

  public record Context(
      String employeeId,
      long employeeVersion,
      String employmentStatus,
      String activationId,
      String subject,
      String role,
      String accessStatus,
      int grantNumber,
      RehireAccessRevocation revocation) {}
}
