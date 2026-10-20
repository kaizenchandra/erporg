package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.ChangeRequest;
import java.time.*;
import java.util.*;

public final class EmployeeHistoryModels {
  private EmployeeHistoryModels() {}

  public record Cursor(
      long throughId, long beforeId, Instant beforeRecordedAt, String fingerprint) {
    public Cursor {
      if (throughId < 1
          || beforeId < 1
          || beforeId > throughId
          || beforeRecordedAt == null
          || beforeRecordedAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
          || beforeRecordedAt.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))
          || fingerprint == null
          || !fingerprint.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid history cursor. Refresh history.");
    }
  }

  public record Query(String field, String action, String order, int size, Cursor cursor) {
    public Query {
      if (field == null
          || (!field.equals("ALL") && !ChangeRequest.FIELDS.contains(field))
          || action == null
          || !Set.of("ALL", "SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN").contains(action)
          || order == null
          || !Set.of("asc", "desc").contains(order)
          || size < 1
          || size > 50)
        throw new IllegalArgumentException("Choose a supported field/action/order and size 1–50.");
    }
  }

  public record Scope(String tenant, String employeeId, List<String> fields) {}

  public record Item(
      String id,
      String action,
      String field,
      String oldValue,
      String newValue,
      String reason,
      String actor,
      Instant recordedAt,
      String correlationId,
      LocalDate effectiveDate) {}

  public record Page(
      List<Item> items, long total, int size, String order, List<String> fields, Cursor next) {}
}
