package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.CalendarRetirement;
import java.math.BigDecimal;
import java.time.Instant;

public final class CalendarRetirementModels {
  private CalendarRetirementModels() {}

  public record Retire(
      BigDecimal version, String effectiveDate, String reason, String idempotencyKey) {}

  public record Entry(
      CalendarRetirement retirement,
      String createdBy,
      String reason,
      Instant recordedAt,
      String correlationId) {}
}
