package com.atlas.modules.payroll.domain.model;

import com.atlas.shared.domain.InputValidation;
import java.math.*;
import java.util.*;

public final class CompensationCalculation {
  public static final String RULE =
      "DEMO-COMPENSATION-1;RATE-4DP;USD-2DP-HALF-EVEN;NO-STATUTORY-RULES";

  private CompensationCalculation() {}

  public static BigDecimal hourly(BigDecimal salary, BigDecimal hours) {
    if (salary == null
        || hours == null
        || salary.signum() <= 0
        || salary.compareTo(new BigDecimal("999999999.99")) > 0
        || salary.scale() > 2
        || hours.compareTo(BigDecimal.ONE) < 0
        || hours.compareTo(new BigDecimal("8784")) > 0
        || hours.scale() > 2)
      throw new InputValidation(
          Map.of(
              "compensation",
              "Supply positive annual salary (2 decimal places) and explicitly approved annual paid hours between 1 and 8784."));
    var r = salary.divide(hours, 4, RoundingMode.HALF_EVEN);
    if (r.signum() <= 0 || r.compareTo(new BigDecimal("1000000")) > 0)
      throw new InputValidation(
          Map.of(
              "compensation",
              "Configured annual paid hours must produce a positive hourly rate no greater than 1000000."));
    return r;
  }

  public static BigDecimal earned(int minutes, BigDecimal rate) {
    return rate.multiply(BigDecimal.valueOf(minutes))
        .divide(BigDecimal.valueOf(60), 12, RoundingMode.HALF_EVEN);
  }

  public static BigDecimal money(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_EVEN);
  }
}
