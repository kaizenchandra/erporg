package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.payroll.domain.model.PayrollCalculation;
import com.atlas.shared.domain.InputValidation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PayrollCalculationTest {
  @Test
  void independentlyEstablishedDecimalFixturesAndHalfEvenTies() {
    assertEquals(
        new BigDecimal("1.00"),
        PayrollCalculation.calculate(60, new BigDecimal("1.005"), BigDecimal.ZERO).gross());
    assertEquals(
        new BigDecimal("1.02"),
        PayrollCalculation.calculate(60, new BigDecimal("1.015"), BigDecimal.ZERO).gross());
    var r = PayrollCalculation.calculate(90, new BigDecimal("20.00"), new BigDecimal("3.25"));
    assertEquals(new BigDecimal("30.00"), r.gross());
    assertEquals(new BigDecimal("26.75"), r.net());
  }

  @Test
  void invalidOrNonpositiveInputsCannotBeSilentlyRounded() {
    assertThrows(
        InputValidation.class,
        () -> PayrollCalculation.calculate(0, BigDecimal.ONE, BigDecimal.ZERO));
    assertThrows(
        InputValidation.class,
        () -> PayrollCalculation.calculate(60, new BigDecimal("-1"), BigDecimal.ZERO));
    assertThrows(
        InputValidation.class,
        () -> PayrollCalculation.calculate(60, BigDecimal.ONE, new BigDecimal("0.001")));
    assertFalse(
        PayrollCalculation.calculate(60, BigDecimal.ONE, new BigDecimal("2"))
            .exceptions()
            .isEmpty());
  }
}
