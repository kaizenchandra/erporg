package com.atlas.modules.corehr.application.model;

public record RequestQuery(String status, int page, int size) {
  public RequestQuery {
    if (status == null
        || !java.util.Set.of("ALL", "SUBMITTED", "APPROVED", "REJECTED").contains(status)
        || page < 0
        || page > 100000
        || size < 1
        || size > 100)
      throw new IllegalArgumentException("Use a supported status, page 0–100000 and size 1–100.");
  }
}
