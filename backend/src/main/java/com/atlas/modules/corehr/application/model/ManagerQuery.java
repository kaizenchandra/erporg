package com.atlas.modules.corehr.application.model;

public record ManagerQuery(String search, int page, int size) {
  public ManagerQuery {
    if (search == null
        || search.length() > 120
        || page < 0
        || page > 100000
        || size < 1
        || size > 100)
      throw new IllegalArgumentException("Use valid search, page 0–100000 and size 1–100.");
    search = search.trim();
  }
}
