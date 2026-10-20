package com.atlas.modules.corehr.domain.model;

import java.time.*;
import java.util.*;

/** Declared reference data only; does not establish registration or statutory policy. */
public record LegalEntity(
    String code,
    String name,
    String countryCode,
    String currencyCode,
    String timeZone,
    LocalDate effectiveFrom) {
  public LegalEntity {
    var errors = new LinkedHashMap<String, String>();
    code = text(code, "code", 30, errors);
    name = text(name, "name", 120, errors);
    countryCode = text(countryCode, "countryCode", 2, errors).toUpperCase(Locale.ROOT);
    currencyCode = text(currencyCode, "currencyCode", 3, errors).toUpperCase(Locale.ROOT);
    timeZone = text(timeZone, "timeZone", 80, errors);
    if (!code.matches("[A-Z0-9-]{2,30}"))
      errors.put("code", "Use 2–30 uppercase letters, digits or hyphens.");
    if (!countryCode.matches("[A-Z]{2}"))
      errors.put("countryCode", "Choose a two-letter country/region code.");
    if (!currencyCode.matches("[A-Z]{3}"))
      errors.put("currencyCode", "Choose a three-letter currency code.");
    if (!timeZone.matches("[A-Za-z0-9_+/-]{1,80}"))
      errors.put("timeZone", "Choose a region time-zone ID.");
    if (effectiveFrom == null) errors.put("effectiveFrom", "Enter an effective date.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
  }

  private static String text(String value, String field, int max, Map<String, String> errors) {
    String v = value == null ? "" : value.trim();
    if (v.isBlank()
        || v.length() > max
        || (value != null && value.codePoints().anyMatch(Character::isISOControl)))
      errors.put(field, "Enter 1–" + max + " characters without control characters.");
    return v;
  }

  public void validateNew(LocalDate today) {
    var errors = new LinkedHashMap<String, String>();
    if (!Arrays.asList(Locale.getISOCountries()).contains(countryCode))
      errors.put("countryCode", "Choose a recognised country/region code.");
    if (Currency.getAvailableCurrencies().stream()
        .noneMatch(c -> c.getCurrencyCode().equals(currencyCode)))
      errors.put("currencyCode", "Choose a currency from the reference vocabulary.");
    if (!ZoneId.getAvailableZoneIds().contains(timeZone))
      errors.put("timeZone", "Choose an available region time-zone ID.");
    if (effectiveFrom.isBefore(today) || effectiveFrom.isAfter(today.plusYears(1)))
      errors.put("effectiveFrom", "Start must be today or within the next year.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
  }
}
