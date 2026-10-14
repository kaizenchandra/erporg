package com.atlas.modules.corehr.domain.model;

import java.time.LocalDate;

public record Department(
    String code, String name, String parentCode, String costCentre, LocalDate effectiveFrom) {
  public Department {
    if (code == null || !code.matches("[A-Z0-9-]{2,30}"))
      throw new IllegalArgumentException(
          "Department code must contain 2–30 uppercase letters, digits or hyphens.");
    if (name == null || name.isBlank() || name.length() > 120)
      throw new IllegalArgumentException("Department name must contain 1–120 characters.");
    if (costCentre == null || !costCentre.matches("[A-Z0-9-]{2,30}"))
      throw new IllegalArgumentException(
          "Cost-centre code must contain 2–30 uppercase letters, digits or hyphens.");
    if (effectiveFrom == null)
      throw new IllegalArgumentException("Department start date is required.");
    if (code.equals(parentCode))
      throw new IllegalArgumentException("A department cannot report to itself.");
    name = name.trim();
  }
}
