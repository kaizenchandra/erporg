package com.atlas.modules.corehr.domain.model;

import java.time.LocalDate;
import java.util.Map;

/** Supplied personal data only; no country-specific age or eligibility policy. */
public final class BirthDate {
  private BirthDate() {}

  public static LocalDate parse(String value) {
    try {
      if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        throw new IllegalArgumentException();
      var date = LocalDate.parse(value);
      if (date.getYear() < 1) throw new IllegalArgumentException();
      return date;
    } catch (RuntimeException ex) {
      throw new ChangeRequest.Validation(
          Map.of("newValue", "Use a valid ISO birth date (YYYY-MM-DD), year 0001–9999."));
    }
  }

  public static void validate(String value, LocalDate today, LocalDate originalStart) {
    var date = parse(value);
    if (date.isAfter(today) || date.isAfter(originalStart))
      throw new ChangeRequest.Validation(
          Map.of("newValue", "Birth date cannot follow today or the original employment start."));
  }
}
