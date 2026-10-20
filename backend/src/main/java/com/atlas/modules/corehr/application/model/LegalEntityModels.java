package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.*;
import java.time.Instant;
import java.util.*;

public final class LegalEntityModels {
  private LegalEntityModels() {}

  public record Input(
      String code,
      String name,
      String countryCode,
      String currencyCode,
      String timeZone,
      java.time.LocalDate effectiveFrom) {}

  public record Create(Input entity, String reason, String idempotencyKey) {}

  public record Entry(
      LegalEntity entity,
      String createdBy,
      String reason,
      Instant recordedAt,
      String correlationId) {}

  public record Page(
      List<Entry> items,
      long total,
      int page,
      int size,
      Map<String, java.time.LocalDate> retirementDates) {
    public Page(List<Entry> items, long total, int page, int size) {
      this(items, total, page, size, Map.of());
    }
  }

  public record Choice(String code, String name) {}

  public record Options(List<Choice> countries, List<Choice> currencies, List<String> timeZones) {}

  public record Query(String search, String status, int page, int size) {
    public Query {
      if (search == null
          || search.length() > 120
          || status == null
          || !Set.of("ALL", "CURRENT", "PLANNED", "RETIRED").contains(status)
          || page < 0
          || page > 10000
          || size < 1
          || size > 50)
        throw new IllegalArgumentException(
            "Use a bounded search, ALL/CURRENT/PLANNED/RETIRED, page 0–10000 and size 1–50.");
      search = search.trim();
    }
  }
}
