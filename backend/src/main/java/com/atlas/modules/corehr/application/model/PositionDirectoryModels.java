package com.atlas.modules.corehr.application.model;

import java.time.LocalDate;
import java.util.List;

public final class PositionDirectoryModels {
  private PositionDirectoryModels() {}

  public record Query(
      String search, String departmentCode, String timing, int page, int size, String direction) {
    public Query {
      if (search == null
          || search.length() > 120
          || departmentCode == null
          || (!departmentCode.isEmpty() && !departmentCode.matches("[A-Z0-9-]{2,30}"))
          || !List.of("ALL", "CURRENT", "PLANNED").contains(timing == null ? "" : timing)
          || page < 0
          || page > 100000
          || size < 1
          || size > 50
          || !("asc".equals(direction) || "desc".equals(direction)))
        throw new IllegalArgumentException(
            "Choose valid position filters, page 0–100000, size 1–50 and order asc or desc.");
      search = search.trim();
    }
  }

  public record Batch(List<PositionListing> items, long total) {
    public Batch {
      items = List.copyOf(items);
    }
  }

  public record Item(
      String code,
      String title,
      String departmentCode,
      String departmentName,
      LocalDate effectiveFrom,
      String timing) {}

  public record Page(List<Item> items, long total, int page, int size, LocalDate evaluatedOn) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
