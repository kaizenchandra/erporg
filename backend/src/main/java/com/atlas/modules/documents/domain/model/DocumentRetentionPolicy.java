package com.atlas.modules.documents.domain.model;

import com.atlas.shared.domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public final class DocumentRetentionPolicy {
  private DocumentRetentionPolicy() {}

  public static int expected(BigDecimal value) {
    try {
      int n = value.intValueExact();
      if (n < 0 || n > 100) throw new ArithmeticException();
      return n;
    } catch (NullPointerException | ArithmeticException e) {
      throw new InputValidation(
          Map.of("expectedVersion", "An exact governance version from 0 to 100 is required."));
    }
  }

  public static void version(int current, int expected) {
    if (current != expected || current >= 100)
      throw new CommandConflict(
          "Governance changed or reached its 100-event limit; refresh the review.");
  }

  public static void retain(LocalDate previous, LocalDate date, LocalDate today) {
    if (date == null || date.getYear() < 1 || date.getYear() > 9999 || date.isBefore(today))
      throw new InputValidation(
          Map.of("retainUntil", "Choose today or a future retain-until date."));
    if (previous != null && date.isBefore(previous))
      throw new CommandConflict("Retain-until dates cannot be shortened in this workflow.");
  }

  public static void release(
      boolean exists,
      boolean active,
      String placerEmployee,
      String actorEmployee,
      String placerSubject,
      String actorSubject) {
    if (!exists || !active)
      throw new CommandConflict("Hold is missing or already released; refresh the review.");
    if (placerEmployee.equals(actorEmployee) || placerSubject.equals(actorSubject))
      throw new AccessDenied();
  }

  public static String state(LocalDate date, int activeHolds, LocalDate today) {
    if (activeHolds > 0) return "ON_HOLD";
    if (date == null) return "NO_POLICY";
    return today.isAfter(date) ? "REVIEW_DUE" : "RETAIN";
  }
}
