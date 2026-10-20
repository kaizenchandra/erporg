package com.atlas.modules.corehr.domain.model;

import java.time.LocalDate;
import java.util.Map;

/** Declared employing reference only; no verified registration or statutory activation. */
public final class EntityAssignment {
  private EntityAssignment() {}

  public static void shape(String code) {
    if (code == null || (!code.isEmpty() && !code.matches("[A-Z0-9-]{2,30}")))
      throw new ChangeRequest.Validation(
          Map.of("newValue", "Choose a canonical declared entity code or explicit unassignment."));
  }

  public static void validate(
      String code,
      LocalDate date,
      LocalDate start,
      LocalDate today,
      LegalEntity entity,
      boolean retirementBlocked) {
    shape(code);
    ChangeRequest.validateDate(date, today);
    if (date.isBefore(start))
      throw new ChangeRequest.Validation(
          Map.of("effectiveDate", "Entity assignment cannot precede this employment episode."));
    if (code.isEmpty()) return;
    if (entity == null || !entity.code().equals(code))
      throw new ChangeRequest.Validation(
          Map.of("newValue", "Choose a declared entity in this organisation."));
    if (retirementBlocked)
      throw new ChangeRequest.Conflict(
          "A pending or approved retirement blocks this entity. Choose another reference or reject and resubmit.");
    if (date.isBefore(entity.effectiveFrom()))
      throw new ChangeRequest.Validation(
          Map.of(
              "effectiveDate", "Assignment cannot precede the declared reference effective date."));
  }
}
