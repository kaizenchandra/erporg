package com.atlas.modules.documents.application.model;

public record RetryDocumentScan(
    java.math.BigDecimal expectedAttempt, String reason, String idempotencyKey) {
  public RetryDocumentScan(int expectedAttempt, String reason, String key) {
    this(java.math.BigDecimal.valueOf(expectedAttempt), reason, key);
  }

  public int version() {
    try {
      if (expectedAttempt == null) throw new ArithmeticException();
      int value = expectedAttempt.intValueExact();
      if (value < 0 || value > 3) throw new ArithmeticException();
      return value;
    } catch (ArithmeticException e) {
      throw new com.atlas.shared.domain.InputValidation(
          java.util.Map.of("expectedAttempt", "Enter a whole scan attempt version from 0 to 3."));
    }
  }
}
