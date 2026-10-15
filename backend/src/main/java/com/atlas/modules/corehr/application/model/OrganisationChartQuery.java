package com.atlas.modules.corehr.application.model;

import com.atlas.shared.domain.InputValidation;
import java.util.Map;

public record OrganisationChartQuery(String parentId, String search, int page, int size) {
  public OrganisationChartQuery {
    if (parentId == null
        || search == null
        || parentId.length() > 40
        || (!parentId.isEmpty() && !parentId.matches("[A-Za-z0-9-]{1,40}"))
        || search.length() > 120
        || search.chars().anyMatch(Character::isISOControl)
        || page < 0
        || page > 100000
        || size < 1
        || size > 50)
      throw new InputValidation(
          Map.of("query", "Use a valid parent/search, page 0–100000 and size 1–50."));
    search = search.trim();
    if (!parentId.isEmpty() && !search.isEmpty())
      throw new InputValidation(
          Map.of("query", "Choose either a parent branch or a global search."));
  }
}
