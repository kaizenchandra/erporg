package com.atlas.modules.corehr.application.model;

import java.time.*;
import java.util.*;

public final class ReferenceHistoryModels {
  private ReferenceHistoryModels() {}

  public record Query(
      String kind, String code, String order, int page, int size, String throughId) {
    public Query {
      if (!Set.of("DEPARTMENT", "POSITION").contains(kind == null ? "" : kind)
          || code == null
          || (!code.isEmpty() && !code.matches("[A-Z0-9-]{2,30}"))
          || !("asc".equals(order) || "desc".equals(order))
          || page < 0
          || page > 100000
          || size < 1
          || size > 50
          || (page > 0 && throughId == null))
        throw new IllegalArgumentException(
            "Choose a reference kind/code, order asc/desc, page 0–100000 and size 1–50; later pages require the history boundary.");
      if (throughId != null) {
        if (!throughId.matches("0|[1-9][0-9]{0,18}"))
          throw new IllegalArgumentException("Refresh history: invalid boundary.");
        try {
          Long.parseLong(throughId);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Refresh history: invalid boundary.");
        }
      }
    }
  }

  public record Item(
      String id,
      String code,
      String actor,
      String reason,
      Instant recordedAt,
      LocalDate effectiveFrom,
      String correlationId) {}

  public record Page(
      List<Item> items, long total, int page, int size, String kind, String throughId) {}
}
