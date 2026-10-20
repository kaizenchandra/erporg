package com.atlas.modules.corehr.domain.model;

/** Supplied ASCII contact data, not verified ownership or a sign-in identifier. */
public final class PersonalEmail {
  private PersonalEmail() {}

  private static final String ATOM = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";
  private static final String LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";

  public static boolean valid(String value) {
    if (value == null || value.length() > 120) return false;
    if (value.isEmpty()) return true;
    int at = value.indexOf('@');
    return at > 0
        && at <= 64
        && value.matches(ATOM + "(?:\\." + ATOM + ")*@" + LABEL + "(?:\\." + LABEL + ")+");
  }

  public static void validate(String value) {
    if (value == null
        || value.codePoints().anyMatch(Character::isISOControl)
        || !valid(value.trim()))
      throw new ChangeRequest.Validation(
          java.util.Map.of(
              "newValue",
              "Enter an ASCII personal email of at most 120 characters with a multi-label domain, or explicitly remove the value."));
  }
}
