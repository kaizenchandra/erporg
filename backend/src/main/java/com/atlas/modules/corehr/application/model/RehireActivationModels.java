package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.RehireActivation;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class RehireActivationModels {
  private RehireActivationModels() {}

  public record Submit(String subject, String reason, BigDecimal version, String idempotencyKey) {}

  public record Review(String decision, String reason, BigDecimal version, String idempotencyKey) {}

  public record Completion(
      String activationId,
      String rehireId,
      String employeeId,
      String subject,
      String role,
      LocalDate effectiveDate,
      long inputVersion,
      long newVersion,
      String actor,
      String reason,
      Instant recordedAt,
      String correlationId) {}

  public record Context(
      String rehireId,
      String employeeId,
      LocalDate startDate,
      String employmentStatus,
      long employeeVersion,
      List<String> identities,
      Completion completion,
      List<RehireActivation> history) {}
}
