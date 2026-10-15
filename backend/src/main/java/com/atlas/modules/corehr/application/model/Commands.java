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
      Long version,
      String idempotencyKey) {
    public Submit(
        String employeeId,
        String field,
        String newValue,
        LocalDate effectiveDate,
        String reason,
        long version,
        String idempotencyKey) {
      this(
          employeeId,
          field,
          newValue,
          effectiveDate,
          reason,
          Long.valueOf(version),
          idempotencyKey);
    }
  }

  public record Withdraw(String reason, String idempotencyKey) {}

  public record Decide(String decision, String comment) {}
}
