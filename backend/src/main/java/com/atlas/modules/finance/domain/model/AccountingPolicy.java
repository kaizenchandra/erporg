package com.atlas.modules.finance.domain.model;

import com.atlas.shared.domain.InputValidation;
import java.math.*;
import java.util.*;

public final class AccountingPolicy {
  private AccountingPolicy() {}

  public static void require(boolean valid, String field, String message) {
    if (!valid) throw new InputValidation(Map.of(field, message));
  }

  public static String text(String v, String f, int max) {
    require(
        v != null
            && !v.isBlank()
            && v.length() <= max
            && v.codePoints().noneMatch(Character::isISOControl),
        f,
        "Enter 1–" + max + " plain text characters.");
    return v.strip();
  }

  public static String code(String v) {
    v = text(v, "code", 30);
    require(v.matches("[A-Z0-9-]{2,30}"), "code", "Use 2–30 uppercase letters, digits or hyphens.");
    return v;
  }

  public static String key(String v) {
    require(
        v != null && v.matches("[a-zA-Z0-9-]{8,80}"),
        "idempotencyKey",
        "Supply a valid command key.");
    return v;
  }

  public static long version(BigDecimal v) {
    try {
      require(v != null && v.signum() >= 0, "version", "Supply an exact nonnegative revision.");
      return v.longValueExact();
    } catch (ArithmeticException e) {
      throw new InputValidation(Map.of("version", "Supply an exact nonnegative revision."));
    }
  }

  public static BigDecimal money(BigDecimal v, String field) {
    require(
        v != null && v.signum() >= 0 && v.compareTo(new BigDecimal("999999999999.99")) <= 0,
        field,
        "Supply a nonnegative amount at most 999999999999.99.");
    try {
      return v.setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new InputValidation(Map.of(field, "Use at most two fractional decimal places."));
    }
  }

  public static void balanced(List<BigDecimal> debits, List<BigDecimal> credits) {
    require(debits.size() >= 2 && debits.size() <= 100, "lines", "Supply 2–100 journal lines.");
    var d = debits.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    var c = credits.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    require(
        d.signum() > 0 && d.compareTo(c) == 0,
        "lines",
        "Debits and credits must be equal and positive.");
  }
}
