package com.atlas.modules.payroll.domain.model;

import com.atlas.shared.domain.InputValidation;
import java.math.*;
import java.util.*;

/**
 * Demonstration fixture only: actual approved minutes × entered hourly rate, less voluntary fixed
 * deduction.
 */
public record PayrollCalculation(
    BigDecimal gross, BigDecimal deduction, BigDecimal net, List<String> exceptions) {
  public static final String RULE = "DEMO-HOURLY-1;USD-2DP-HALF-EVEN;NO-STATUTORY-RULES";

  public static PayrollCalculation calculate(int minutes, BigDecimal rate, BigDecimal deduction) {
    if (minutes <= 0
        || minutes > 10080
        || rate == null
        || rate.signum() <= 0
        || rate.scale() > 4
        || rate.compareTo(new BigDecimal("1000000")) > 0)
      throw new InputValidation(
          Map.of(
              "hourlyRate",
              "Use positive hourly rate up to 1,000,000, at most four decimal places, and approved positive time."));
    if (deduction == null
        || deduction.signum() < 0
        || deduction.scale() > 2
        || deduction.compareTo(new BigDecimal("999999999999.99")) > 0)
      throw new InputValidation(
          Map.of("deduction", "Use a nonnegative voluntary deduction with at most two decimals."));
    var gross =
        rate.multiply(BigDecimal.valueOf(minutes))
            .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_EVEN);
    var d = deduction.setScale(2, RoundingMode.UNNECESSARY);
    var net = gross.subtract(d);
    return new PayrollCalculation(
        gross,
        d,
        net,
        net.signum() <= 0
            ? List.of(
                "BLOCKING: voluntary deduction leaves nonpositive net pay; correct the input before submission.")
            : List.of());
  }
}
