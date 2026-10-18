package com.atlas.modules.corehr.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class ResignationCommands {
  private ResignationCommands() {}

  public record Submit(
      LocalDate lastWorkingDate,
      String reason,
      BigDecimal employeeVersion,
      String idempotencyKey) {}

  public record WithdrawalContext(
      com.atlas.modules.corehr.domain.model.ResignationWithdrawal receipt) {}

  public record Withdraw(String reason, BigDecimal version, String idempotencyKey) {}

  public record Review(
      String decision, String comment, BigDecimal version, String idempotencyKey) {}
}
