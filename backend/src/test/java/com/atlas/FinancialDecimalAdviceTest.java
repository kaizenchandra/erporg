package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.shared.web.FinancialDecimalAdvice;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class FinancialDecimalAdviceTest {
  public record Example(long version, BigDecimal amount, List<BigDecimal> lines) {}

  @Test
  void exactAmountsAcrossNestedRecordsNeverPassThroughBinaryFloatingPoint() {
    var amount = new BigDecimal("9007199254740993.01");
    var result =
        FinancialDecimalAdvice.transport(
            new Example(4, amount, List.of(new BigDecimal("0.10"), new BigDecimal("1E+3"))));
    assertEquals(
        Map.of("version", 4L, "amount", "9007199254740993.01", "lines", List.of("0.10", "1000")),
        result);
    assertNull(FinancialDecimalAdvice.transport(null));
  }
}
