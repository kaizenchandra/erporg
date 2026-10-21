package com.atlas.modules.payroll.application.model;

import com.atlas.modules.payroll.api.CompensationAccess.Salary;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class CompensationModels {
  private CompensationModels() {}

  public record Baseline(
      String employeeId,
      String currency,
      BigDecimal annualSalary,
      BigDecimal annualHours,
      LocalDate effectiveDate,
      String reason,
      String idempotencyKey) {}

  public record Decision(String action, String reason, String idempotencyKey) {}

  public record Setup(
      String id,
      Baseline input,
      LocalDate episodeStart,
      String preparedBy,
      String preparedEmployee,
      String status,
      String reviewedBy,
      String reviewedEmployee,
      String reviewReason,
      Instant recordedAt) {}

  public record Daily(LocalDate date, int minutes, Salary salary, BigDecimal hourlyRate) {}

  public record Bonus(String id, String sourceId, BigDecimal amount, LocalDate effectiveDate) {}

  public record Calculation(
      String runId,
      String employeeId,
      String entity,
      String currency,
      PayrollModels.Source source,
      List<Daily> days,
      List<Bonus> bonuses,
      BigDecimal hourlyRate,
      BigDecimal baseGross,
      BigDecimal bonus,
      BigDecimal gross,
      String ruleVersion,
      String actor,
      String reason,
      Instant recordedAt,
      String correlationId) {}

  public record State(List<Setup> setups, List<Salary> salaries) {}
}
