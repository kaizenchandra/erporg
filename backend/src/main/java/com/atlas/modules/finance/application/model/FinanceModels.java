package com.atlas.modules.finance.application.model;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class FinanceModels {
  private FinanceModels() {}

  public record Identity(String subject, String tenant, String employeeId) {}

  public record Grant(String entity, String permission) {}

  public record Reference(
      String code, String currency, LocalDate effectiveFrom, LocalDate retiredOn) {}

  public record Ledger(
      String entity,
      String currency,
      LocalDate effectiveFrom,
      List<String> costCentres,
      List<Account> accounts) {
    public Ledger {
      costCentres = List.copyOf(costCentres);
      accounts = List.copyOf(accounts);
    }
  }

  public record Account(String code, String name, String kind, boolean requiresCostCentre) {}

  public record Setup(
      String entity, List<String> costCentres, String reason, String idempotencyKey) {}

  public record Line(
      String account, String costCentre, BigDecimal debit, BigDecimal credit, String memo) {}

  public record Journal(
      String id,
      String entity,
      String currency,
      LocalDate postingDate,
      String sourceType,
      String sourceId,
      String reversalOf,
      String status,
      long version,
      String preparedBy,
      String preparedEmployee,
      String approvedBy,
      String approvedEmployee,
      List<Line> lines,
      String reason,
      Instant recordedAt) {
    public Journal {
      lines = List.copyOf(lines);
    }
  }

  public record SaveJournal(
      String id,
      String entity,
      LocalDate postingDate,
      BigDecimal version,
      List<Line> lines,
      String reason,
      String idempotencyKey) {}

  public record PayrollCommand(
      String runId,
      String entity,
      String currency,
      LocalDate postingDate,
      String costCentre,
      BigDecimal gross,
      BigDecimal deduction,
      BigDecimal net,
      String reason,
      String idempotencyKey) {}

  public record Action(BigDecimal version, String action, String reason, String idempotencyKey) {}

  public record Period(
      LocalDate month,
      boolean closed,
      long version,
      String reason,
      String actor,
      Instant recordedAt) {}

  public record PeriodContext(Period current) {}

  public record SetPeriod(
      String entity,
      LocalDate month,
      Boolean closed,
      BigDecimal version,
      String reason,
      String idempotencyKey) {}

  public record Context(List<Grant> grants, List<Ledger> ledgers) {}

  public record Page<T>(List<T> items, long total, int page, int size) {}

  public record Balance(String account, String costCentre, BigDecimal debit, BigDecimal credit) {}

  public record TrialBalance(
      String entity,
      String currency,
      LocalDate from,
      LocalDate through,
      List<Balance> items,
      BigDecimal debit,
      BigDecimal credit) {}

  public record Audit(
      String id,
      String entity,
      String documentId,
      String action,
      String actor,
      String reason,
      String before,
      String after,
      Instant recordedAt,
      String correlationId) {}

  public record Receipt(String fingerprint, String result) {}
}
