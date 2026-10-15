package com.atlas.modules.benefits.domain.model;

import com.atlas.shared.domain.InputValidation;
import java.util.Map;

public record Dependant(String name, String relationship) {
  public Dependant {
    if (name == null
        || name.isBlank()
        || name.length() > 120
        || name.chars().anyMatch(Character::isISOControl))
      throw new InputValidation(
          Map.of("dependants", "Dependant names require 1–120 characters without controls."));
    name = name.trim();
    if (!java.util.Set.of("PARTNER", "CHILD", "OTHER")
        .contains(relationship == null ? "" : relationship))
      throw new InputValidation(Map.of("dependants", "Choose Partner, Child or Other."));
  }
}
