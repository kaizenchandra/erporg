package com.atlas.modules.corehr.application.model;

public record DirectoryQuery(
    String search, String department, String status, int page, int size, String direction) {
  public DirectoryQuery {
    if (search == null
        || department == null
        || status == null
        || search.length() > 120
        || department.length() > 120
        || status.length() > 40
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
