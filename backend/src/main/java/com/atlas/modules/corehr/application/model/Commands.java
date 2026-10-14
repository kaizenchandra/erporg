package com.atlas.modules.corehr.application.model;

import java.time.LocalDate;

public final class Commands {
  private Commands() {}

  public record Submit(
      String employeeId,
      String field,
      String newValue,
      LocalDate effectiveDate,
      String reason,
      long version,
      String idempotencyKey) {}

  public record Decide(String decision, String comment) {}
}
