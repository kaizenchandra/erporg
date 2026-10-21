package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.payroll.domain.model.CompensationCalculation;
import com.atlas.shared.domain.InputValidation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CompensationCalculationTest {
  @Test
  void explicitAnnualHoursUseHalfEvenAtFourPlacesThenMoneyAtTwo() {
    assertEquals(
        new BigDecimal("0.1234"),
        CompensationCalculation.hourly(new BigDecimal("24.69"), new BigDecimal("200")));
    assertEquals(
        new BigDecimal("0.1236"),
        CompensationCalculation.hourly(new BigDecimal("24.71"), new BigDecimal("200")));
    assertEquals(
        new BigDecimal("55.92"),
        CompensationCalculation.money(
            CompensationCalculation.earned(61, new BigDecimal("55.0000"))));
    assertEquals(new BigDecimal("1.00"), CompensationCalculation.money(new BigDecimal("1.005")));
    assertEquals(new BigDecimal("1.02"), CompensationCalculation.money(new BigDecimal("1.015")));
  }

  @Test
  void invalidUnapprovedBasisOrSubCentSalaryIsRejected() {
    assertThrows(
        InputValidation.class,
        () -> CompensationCalculation.hourly(new BigDecimal("100000"), BigDecimal.ZERO));
    assertThrows(
        InputValidation.class,
        () -> CompensationCalculation.hourly(new BigDecimal("100000.001"), new BigDecimal("2000")));
    assertThrows(
        InputValidation.class,
        () -> CompensationCalculation.hourly(new BigDecimal("0.01"), new BigDecimal("8784")));
  }
}
