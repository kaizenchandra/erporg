package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.ExitTask;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class ExitTaskModels {
  private ExitTaskModels() {}

  public record Create(
      String assigneeId,
      String title,
      String instructions,
      LocalDate dueDate,
      String reason,
      BigDecimal version,
      String idempotencyKey) {}

  public record Manage(
      String action, String assigneeId, String reason, BigDecimal version, String idempotencyKey) {}

  public record Management(
      String taskId,
      int ordinal,
      String action,
      String oldAssigneeId,
      String newAssigneeId,
      String oldStatus,
      String newStatus,
      String actor,
      String actorEmployeeId,
      String reason,
      long inputVersion,
      Instant recordedAt,
      String correlationId) {}

  public record Complete(String comment, BigDecimal version, String idempotencyKey) {}

  public record Item(ExitTask task, boolean overdue) {}

  public record Page(List<Item> items, long total, int page, int size, LocalDate asOf) {}

  public record Case(List<Item> items, long version, LocalDate asOf) {}

  public record Audit(
      String oldStatus,
      String newStatus,
      String actor,
      String reason,
      LocalDate effectiveDate,
      Instant recordedAt,
      String correlationId) {}

  public record Query(String status, int page, int size) {
    public Query {
      if (status == null
          || !Set.of("ALL", "OPEN", "COMPLETED", "CANCELLED").contains(status)
          || page < 0
          || page > 100000
          || size < 1
          || size > 100)
        throw new IllegalArgumentException(
            "Use ALL, OPEN, COMPLETED or CANCELLED, page 0–100000 and size 1–100.");
    }
  }
}
