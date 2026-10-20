package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.finance.domain.model.AccountingPolicy;
import com.atlas.shared.domain.InputValidation;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountingPolicyTest {
  @Test
  void moneyDoesNotRoundInvalidPrecisionOrAcceptInvalidRevisions() {
    assertEquals(new BigDecimal("1.00"), AccountingPolicy.money(BigDecimal.ONE, "amount"));
    assertThrows(
        InputValidation.class, () -> AccountingPolicy.money(new BigDecimal("1.001"), "amount"));
    assertThrows(
        InputValidation.class, () -> AccountingPolicy.money(new BigDecimal("-0.01"), "amount"));
    assertThrows(InputValidation.class, () -> AccountingPolicy.version(new BigDecimal("0.5")));
    assertThrows(
        InputValidation.class,
        () -> AccountingPolicy.version(new BigDecimal("9223372036854775808")));
    assertEquals(Long.MAX_VALUE, AccountingPolicy.version(new BigDecimal("9223372036854775807")));
  }

  @Test
  void exactBalancingAndPlainCommandInputsAreRequired() {
    AccountingPolicy.balanced(
        List.of(new BigDecimal("0.10"), BigDecimal.ZERO),
        List.of(BigDecimal.ZERO, new BigDecimal("0.10")));
    assertThrows(
        InputValidation.class,
        () ->
            AccountingPolicy.balanced(
                List.of(BigDecimal.ONE, BigDecimal.ZERO),
                List.of(BigDecimal.ZERO, new BigDecimal("1.01"))));
    assertThrows(
        InputValidation.class, () -> AccountingPolicy.text("reason\nforged", "reason", 500));
    assertThrows(InputValidation.class, () -> AccountingPolicy.key("short"));
  }
}
