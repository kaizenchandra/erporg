package com.atlas.modules.corehr.domain.model;

import java.util.*;

/** A single address value; its canonical representation is private and versioned. */
public record PostalAddress(
    String line1,
    String line2,
    String locality,
    String region,
    String postalCode,
    String countryCode) {
  public static final String PREFIX = "@POSTAL/1\n";
  public static final String RESERVED = "@POSTAL/";
  private static final Set<String> CURRENT_COUNTRIES =
      Set.copyOf(Arrays.asList(Locale.getISOCountries()));

  public PostalAddress {
    var errors = new LinkedHashMap<String, String>();
    line1 = text(line1, "line1", 120, true, errors);
    line2 = text(line2, "line2", 120, false, errors);
    locality = text(locality, "locality", 80, true, errors);
    region = text(region, "region", 80, false, errors);
    postalCode = text(postalCode, "postalCode", 30, false, errors);
    if (countryCode != null && countryCode.codePoints().anyMatch(Character::isISOControl))
      errors.put("countryCode", "Country/region codes cannot contain control characters.");
    countryCode = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
    if (!countryCode.matches("[A-Z]{2}"))
      errors.put("countryCode", "Choose a two-letter country/region code.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
  }

  private static String text(
      String value, String field, int max, boolean required, Map<String, String> errors) {
    String normalized = value == null ? "" : value.trim();
    if ((required && normalized.isBlank())
        || normalized.length() > max
        || (value != null && value.codePoints().anyMatch(Character::isISOControl)))
      errors.put(
          field,
          "Enter "
              + (required ? "1" : "0")
              + "–"
              + max
              + " characters without control characters.");
    return normalized;
  }

  public void requireCurrentCountry() {
    if (!CURRENT_COUNTRIES.contains(countryCode))
      throw new ChangeRequest.Validation(
          Map.of("countryCode", "Choose a recognised country/region code."));
  }

  public static Set<String> countryCodes() {
    return CURRENT_COUNTRIES;
  }

  public String canonicalValue() {
    return PREFIX + String.join("\n", line1, line2, locality, region, postalCode, countryCode);
  }

  public static Optional<PostalAddress> decode(String value) {
    if (value == null || !value.startsWith(RESERVED)) return Optional.empty();
    if (!value.startsWith(PREFIX))
      throw new ChangeRequest.Validation(Map.of("newValue", "Unsupported postal address format."));
    var parts = value.substring(PREFIX.length()).split("\n", -1);
    if (parts.length != 6)
      throw new ChangeRequest.Validation(
          Map.of("newValue", "Postal address must contain exactly six components."));
    var address = new PostalAddress(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]);
    if (!address.canonicalValue().equals(value))
      throw new ChangeRequest.Validation(
          Map.of("newValue", "Postal address must use its canonical form."));
    return Optional.of(address);
  }
}
