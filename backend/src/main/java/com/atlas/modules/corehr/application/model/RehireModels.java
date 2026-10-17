package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.Rehire;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class RehireModels {
  private RehireModels() {}

  public record Submit(
      LocalDate startDate,
      String title,
      String department,
      String location,
      String managerId,
      String reason,
      BigDecimal version,
      String idempotencyKey) {}

  public record Review(String decision, String reason, BigDecimal version, String idempotencyKey) {}

  public record Episode(
      int number,
      LocalDate startDate,
      LocalDate inactiveDate,
      String state,
      String source,
      String requestId,
      Instant recordedAt) {}

  public record Context(
      String employeeId,
      long employeeVersion,
      String employmentStatus,
      LocalDate priorInactiveDate,
      boolean legacyAccessBlocked,
      List<Episode> episodes,
      List<Rehire> history) {}
}
