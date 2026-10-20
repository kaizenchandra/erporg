package com.atlas.modules.corehr.application.model;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class DepartmentParentModels {
  private DepartmentParentModels() {}

  public record Command(
      BigDecimal version,
      String parentCode,
      LocalDate effectiveDate,
      String reason,
      String idempotencyKey) {}

  public record Snapshot(
      String code,
      String name,
      String parentCode,
      String parentName,
      LocalDate effectiveFrom,
      long version) {}

  public record Change(
      String code,
      long version,
      String oldParentCode,
      String parentCode,
      LocalDate effectiveDate,
      Instant recordedAt,
      String actor,
      String reason,
      String idempotencyKey,
      String correlationId) {}

  public record Context(
      Snapshot department,
      LocalDate evaluatedOn,
      List<Change> items,
      long total,
      int page,
      int size) {
    public Context {
      items = List.copyOf(items);
    }
  }
}
