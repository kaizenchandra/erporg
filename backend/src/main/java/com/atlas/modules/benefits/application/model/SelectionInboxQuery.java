package com.atlas.modules.benefits.application.model;

import java.util.Set;

public record SelectionInboxQuery(String scope, String status, int page, int size) {
  public SelectionInboxQuery {
    if (!Set.of("OWN", "REVIEW").contains(scope == null ? "" : scope)
        || !Set.of("ALL", "PENDING", "APPROVED", "REJECTED", "WITHDRAWN")
            .contains(status == null ? "" : status)
        || page < 0
        || page > 100000
        || size < 1
        || size > 50)
      throw new IllegalArgumentException(
          "Choose OWN/REVIEW, a selection status, page0–100000 and size1–50.");
  }
}
