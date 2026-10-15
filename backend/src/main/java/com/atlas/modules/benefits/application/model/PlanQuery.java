package com.atlas.modules.benefits.application.model;

public record PlanQuery(String search, String eligibility, int page, int size, String direction) {
  public PlanQuery {
    if (search == null
        || search.length() > 120
        || !java.util.Set.of("ALL", "ELIGIBLE").contains(eligibility == null ? "" : eligibility)
        || page < 0
        || page > 100000
        || size < 1
        || size > 100
        || !java.util.Set.of("asc", "desc").contains(direction == null ? "" : direction))
      throw new IllegalArgumentException(
          "Use valid search/eligibility, page 0–100000, size 1–100 and direction asc or desc.");
    search = search.trim();
  }
}
