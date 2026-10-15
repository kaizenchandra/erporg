package com.atlas.modules.documents.domain.model;

import com.atlas.shared.domain.InputValidation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public final class DocumentTemplatePolicy {
  private DocumentTemplatePolicy() {}

  public static void text(String field, String text, int max, boolean multiline) {
    if (text == null
        || text.isBlank()
        || text.length() > max
        || text.chars()
            .anyMatch(
                c ->
                    Character.isISOControl(c)
                        && (!multiline || (c != '\n' && c != '\r' && c != '\t'))))
      throw new InputValidation(
          Map.of(field, "Enter 1–" + max + " characters without unsupported controls."));
  }

  public static void code(String code) {
    if (code == null || !code.matches("[A-Z][A-Z0-9_-]{1,31}"))
      throw new InputValidation(
          Map.of(
              "code",
              "Use 2–32 uppercase letters, digits, underscores or hyphens, starting with a letter."));
  }

  public static void key(String key) {
    if (key == null || !key.matches("[a-zA-Z0-9-]{8,80}"))
      throw new InputValidation(Map.of("idempotencyKey", "A valid command key is required."));
  }

  public static int version(BigDecimal value) {
    try {
      int n = value.intValueExact();
      if (n < 1 || n > 100) throw new ArithmeticException();
      return n;
    } catch (ArithmeticException | NullPointerException e) {
      throw new InputValidation(
          Map.of("expectedVersion", "An exact document version from 1 to 100 is required."));
    }
  }

  public static String completeness(
      boolean required, LocalDate effective, LocalDate today, String latestStatus) {
    if (effective.isAfter(today)) return "NOT_YET_EFFECTIVE";
    if (!required) return "OPTIONAL";
    if (latestStatus == null) return "MISSING";
    return latestStatus.equals("AVAILABLE") ? "PRESENT" : "BLOCKED";
  }

  public static int classificationVersion(BigDecimal value) {
    try {
      return version(value);
    } catch (InputValidation e) {
      throw new InputValidation(
          Map.of(
              "expectedClassificationVersion",
              "An exact classification version from 1 to 100 is required."));
    }
  }

  public static void revision(
      int latest,
      int expected,
      boolean retired,
      LocalDate lastDate,
      LocalDate date,
      LocalDate today) {
    if (latest != expected || expected >= 100 || retired)
      throw new com.atlas.shared.domain.CommandConflict(
          "Template changed, retired or reached revision limit; refresh history.");
    if (date.isBefore(today) || date.isBefore(lastDate))
      throw new InputValidation(
          Map.of("effectiveDate", "Use today or a future date on/after the latest revision date."));
  }

  public static void correction(int current, int expected) {
    if (current != expected || expected >= 100)
      throw new com.atlas.shared.domain.CommandConflict(
          "Classification changed or reached correction limit; refresh history.");
  }
}
