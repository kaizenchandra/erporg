package com.atlas.modules.documents.application.model;

import java.time.*;

public record DocumentTemplate(
    String code,
    String title,
    String body,
    boolean required,
    LocalDate effectiveDate,
    String actor,
    String reason,
    Instant recordedAt,
    String correlationId,
    int version,
    boolean retired) {
  public DocumentTemplate(
      String code,
      String title,
      String body,
      boolean required,
      LocalDate effectiveDate,
      String actor,
      String reason,
      Instant recordedAt,
      String correlationId) {
    this(
        code,
        title,
        body,
        required,
        effectiveDate,
        actor,
        reason,
        recordedAt,
        correlationId,
        1,
        false);
  }
}
