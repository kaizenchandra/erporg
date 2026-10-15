package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.benefits.domain.model.BenefitPlan;
import com.atlas.shared.domain.InputValidation;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BenefitsDomainTest {
  @Test
  void eligibilityDateAndEmploymentBoundaries() {
    var start = LocalDate.of(2024, 2, 29);
    var plan = new BenefitPlan("BEN-DATE", "Demo", "GBP", new BigDecimal("10.50"), 30, start);
    assertFalse(plan.eligibility("Active", start, start.plusDays(29)).eligible());
    assertTrue(plan.eligibility("Active", start, start.plusDays(30)).eligible());
    assertTrue(plan.eligibility("On leave", start, start.plusDays(30)).eligible());
    assertFalse(plan.eligibility("Onboarding", start, start.plusDays(30)).eligible());
    assertFalse(plan.eligibility("Terminated", start, start.plusDays(30)).eligible());
    assertFalse(plan.eligibility("Active", start.plusDays(40), start.plusDays(30)).eligible());
  }

  @Test
  void decimalPrecisionIsExactAndNeverImplicitlyRounded() {
    var day = LocalDate.of(2026, 10, 14);
    assertEquals(
        "0.00",
        new BenefitPlan("BEN-FREE", "Free", "USD", BigDecimal.ZERO, 0, day)
            .monthlyContribution()
            .toPlainString());
    assertThrows(
        InputValidation.class,
        () -> new BenefitPlan("BEN-BAD", "Bad", "USD", new BigDecimal("0.001"), 0, day));
    assertThrows(
        InputValidation.class,
        () -> new BenefitPlan("BEN-NEG", "Negative", "USD", new BigDecimal("-1"), 0, day));
    assertThrows(
        InputValidation.class,
        () -> new BenefitPlan("BEN-CURRENCY", "Unsupported", "XXX", BigDecimal.ONE, 0, day));
  }
}
