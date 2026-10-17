package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.EmploymentEnd;
import com.atlas.modules.corehr.domain.model.EndWithdrawal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class EmploymentEndModels {
  private EmploymentEndModels() {}

  public record Confirm(String reason, BigDecimal version, String idempotencyKey) {}

  public record ReviewWithdrawal(
      String decision, String reason, BigDecimal version, String idempotencyKey) {}

  public record Context(
      EmploymentEnd record,
      EmploymentEnd currentRecord,
      boolean replaced,
      boolean replacementReviewOpen,
      String phase,
      LocalDate inactiveDate,
      long employeeVersion,
      long openTasks,
      List<EndWithdrawal> withdrawals) {}
}
