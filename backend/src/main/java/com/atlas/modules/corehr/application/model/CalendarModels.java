package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;

public final class CalendarModels {
  private CalendarModels() {}

  public record Input(
      String code,
      String name,
      String timeZone,
      String effectiveFrom,
      String effectiveUntil,
      List<java.math.BigDecimal> workingDays,
      List<String> closedDates) {}

  public record Create(Input calendar, String reason, String idempotencyKey) {}

  public record Entry(
      WorkCalendar calendar,
      String createdBy,
      String reason,
      Instant recordedAt,
      String correlationId) {}

  public record Page(
      List<Entry> items, long total, int page, int size, Map<String, LocalDate> retirementDates) {}

  public record Preview(
      String code,
      LocalDate date,
      String timeZone,
      String classification,
      boolean working,
      int revision,
      LocalDate policyEffectiveFrom) {}

  public record Query(String search, String status, int page, int size) {
    public Query {
      if (search == null
          || search.length() > 120
          || search.codePoints().anyMatch(Character::isISOControl)
          || status == null
          || !Set.of("ALL", "CURRENT", "PLANNED", "EXPIRED", "RETIRED").contains(status)
          || page < 0
          || page > 4000
          || size < 1
          || size > 50)
        throw new IllegalArgumentException(
            "Use a bounded search, ALL/CURRENT/PLANNED/EXPIRED/RETIRED, page 0–4000 and size 1–50.");
      search = search.trim();
    }
  }

  public static List<Integer> wholeDays(List<java.math.BigDecimal> values) {
    if (values == null) return null;
    try {
      return values.stream().map(v -> v == null ? null : v.intValueExact()).toList();
    } catch (ArithmeticException e) {
      throw new ChangeRequest.Validation(
          Map.of("workingDays", "Use whole weekdays, Monday=1 to Sunday=7."));
    }
  }
}
