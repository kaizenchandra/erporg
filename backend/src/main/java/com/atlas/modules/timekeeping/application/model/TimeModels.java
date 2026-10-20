package com.atlas.modules.timekeeping.application.model;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class TimeModels {
  private TimeModels() {}

  public record Actor(
      String subject,
      String tenant,
      String employeeId,
      String role,
      String status,
      LocalDate start) {}

  public record Scope(Actor actor, List<String> employeeIds, boolean all) {
    public Scope {
      employeeIds = List.copyOf(employeeIds);
    }
  }

  public record Shift(
      String id,
      String employeeId,
      LocalDate episodeStart,
      Instant start,
      Instant end,
      String zone,
      long version,
      String reason,
      Instant recordedAt) {}

  public record Punch(String zone, String idempotencyKey) {}

  public record Correction(
      String id,
      BigDecimal version,
      String start,
      String end,
      String zone,
      String reason,
      String idempotencyKey) {}

  public record EntryInput(LocalDate date, BigDecimal hours, String code, BigDecimal minutes) {
    public EntryInput(LocalDate date, BigDecimal hours, String code) {
      this(date, hours, code, null);
    }
  }

  public record Entry(LocalDate date, int minutes, String code) {}

  public record Sheet(
      String id,
      String employeeId,
      LocalDate episodeStart,
      LocalDate week,
      String zone,
      String status,
      long version,
      List<Entry> entries,
      int totalMinutes,
      String reason,
      String decidedBy,
      String comment) {
    public Sheet {
      entries = List.copyOf(entries);
    }
  }

  public record SaveSheet(
      LocalDate week,
      String zone,
      BigDecimal version,
      List<EntryInput> entries,
      String reason,
      String idempotencyKey) {}

  public record Dashboard(
      LocalDate week,
      Sheet sheet,
      Period period,
      long pendingApprovals,
      boolean canReview,
      Instant generatedAt) {}

  public record CopySheet(
      String sourceId,
      BigDecimal sourceVersion,
      LocalDate week,
      String reason,
      String idempotencyKey) {}

  public record ReportQuery(LocalDate from, LocalDate through, String employeeId, String status) {}

  public record ReportRow(
      String id,
      String employeeId,
      LocalDate week,
      String status,
      long version,
      int totalMinutes,
      int allocatedMinutes) {}

  public record Report(
      List<ReportRow> items, int totalMinutes, int allocatedMinutes, Instant generatedAt) {
    public Report {
      items = List.copyOf(items);
    }
  }

  public record ReportDownload(String filename, String content, int rows) {}

  public record Decision(
      BigDecimal version, String action, String comment, String idempotencyKey) {}

  public record SelectedSheet(String id, BigDecimal version) {}

  public record BulkDecision(
      List<SelectedSheet> sheets, String action, String comment, String idempotencyKey) {}

  public record Allocation(
      String sheetId, LocalDate date, int minutes, String code, Instant postedAt) {}

  public record Audit(
      String id,
      String entityId,
      String action,
      String actor,
      String reason,
      String before,
      String after,
      Instant recordedAt,
      String correlationId) {}

  public record Page<T>(List<T> items, long total, int page, int size) {
    public Page {
      items = List.copyOf(items);
    }
  }

  public record Outcome(Shift shift, Sheet sheet) {}

  public record ActorAction(String actor, String action) {}

  public record Receipt(String fingerprint, Outcome outcome) {}

  public record Period(
      LocalDate week,
      Instant deadline,
      int dailyLimit,
      int weeklyLimit,
      boolean recallAllowed,
      boolean closed,
      long version,
      String reason,
      String actor,
      Instant recordedAt,
      String commandKey) {}

  public record SavePeriod(
      LocalDate week,
      Instant deadline,
      java.math.BigDecimal dailyLimit,
      java.math.BigDecimal weeklyLimit,
      Boolean recallAllowed,
      Boolean closed,
      java.math.BigDecimal version,
      String reason,
      String idempotencyKey) {}

  public record PeriodContext(Period current, List<Period> history) {}

  public record SheetContext(Sheet sheet, List<Allocation> allocations, int allocatedMinutes) {}
}
