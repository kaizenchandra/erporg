package com.atlas.modules.corehr.application.model;

import java.time.LocalDate;
import java.util.List;

public final class DepartmentDirectoryModels {
  private DepartmentDirectoryModels() {}

  public record Query(
      String search,
      String timing,
      String relationship,
      String parentCode,
      int page,
      int size,
      String direction) {
    public Query {
      if (search == null
          || search.length() > 120
          || !List.of("ALL", "CURRENT", "PLANNED").contains(timing == null ? "" : timing)
          || !List.of("ALL", "ROOTS").contains(relationship == null ? "" : relationship)
          || parentCode == null
          || (!parentCode.isEmpty() && !parentCode.matches("[A-Z0-9-]{2,30}"))
          || (relationship.equals("ROOTS") && !parentCode.isEmpty())
          || page < 0
          || page > 100000
          || size < 1
          || size > 50
          || !("asc".equals(direction) || "desc".equals(direction)))
        throw new IllegalArgumentException(
            "Choose valid department filters, page 0–100000, size 1–50 and order asc or desc.");
      search = search.trim();
    }
  }

  public record Item(
      String code,
      String name,
      String parentCode,
      String parentName,
      String costCentre,
      LocalDate effectiveFrom,
      String timing,
      long directChildren) {}

  public record Page(List<Item> items, long total, int page, int size, LocalDate evaluatedOn) {}
}
