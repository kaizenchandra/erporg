package com.atlas.modules.payroll.application.model;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class PayrollModels {
  private PayrollModels() {}

  public record Identity(String subject, String tenant, String employeeId) {}

  public record Grant(String entity, String permission) {}

  public record Ledger(
      String entity, String currency, LocalDate effectiveFrom, List<String> costCentres) {}

  public record Worker(String id, String status, LocalDate start, String entityCode) {}

  public record Day(LocalDate date, int minutes, String activity) {}

  public record Source(
      String id,
      String employeeId,
      LocalDate episodeStart,
      LocalDate week,
      long version,
      List<Day> days,
      int totalMinutes) {}

  public record Prepare(
      String id,
      BigDecimal version,
      String entity,
      String sheetId,
      String costCentre,
      LocalDate postingDate,
      BigDecimal hourlyRate,
      BigDecimal deduction,
      String reason,
      String idempotencyKey) {}

  public record Action(BigDecimal version, String action, String reason, String idempotencyKey) {}

  public record Run(
      String id,
      String entity,
      String employeeId,
      LocalDate episodeStart,
      Source source,
      String currency,
      String costCentre,
      LocalDate postingDate,
      BigDecimal hourlyRate,
      BigDecimal gross,
      BigDecimal deduction,
      BigDecimal net,
      String ruleVersion,
      List<String> exceptions,
      String status,
      long version,
      String preparedBy,
      String preparedEmployee,
      String approvedBy,
      String approvedEmployee,
      String journalId,
      String paymentStatus,
      String reason,
      Instant recordedAt) {}

  public record Page(List<Run> items, long total, int page, int size) {}

  public record Audit(
      String id,
      String runId,
      String actor,
      String action,
      String reason,
      String before,
      String after,
      Instant recordedAt,
      String correlationId) {}

  public record Document(String filename, String content) {}

  public record Receipt(String fingerprint, String result) {}
}
