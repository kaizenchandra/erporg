package com.atlas.modules.corehr.application.model;

public record PositionQuery(
    String search, String departmentCode, int page, int size, String direction) {
  public PositionQuery {
    if (search == null
        || departmentCode == null
        || search.length() > 120
        || departmentCode.length() > 30
        || page < 0
        || page > 100000
        || size < 1
        || size > 100
        || !("asc".equals(direction) || "desc".equals(direction)))
      throw new IllegalArgumentException(
          "Use valid filters, page 0–100000, size 1–100 and direction asc or desc.");
    search = search.trim();
  }
}
