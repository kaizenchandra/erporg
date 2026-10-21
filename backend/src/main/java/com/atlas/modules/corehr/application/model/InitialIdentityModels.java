package com.atlas.modules.corehr.application.model;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class InitialIdentityModels {
  private InitialIdentityModels() {}

  public record Submit(
      BigDecimal employeeVersion, String federatedSubject, String reason, String idempotencyKey) {}

  public record Review(String decision, String reason, String idempotencyKey) {}

  public record Request(
      String id,
      String employeeId,
      long employeeVersion,
      String federatedSubject,
      String issuer,
      LocalDate effectiveFrom,
      String preparedBy,
      String preparedEmployee,
      String reason,
      String idempotencyKey,
      Instant recordedAt,
      String correlationId) {}

  public record Decision(
      String requestId,
      String decision,
      String actor,
      String actorEmployee,
      String reason,
      String idempotencyKey,
      Instant recordedAt,
      String correlationId) {}

  public record Binding(Request request, Decision decision) {}

  public record Context(
      String issuer,
      long employeeVersion,
      String employmentStatus,
      LocalDate startDate,
      List<Binding> bindings) {}
}
