package com.atlas.modules.documents.application.model;

public record VersionDocument(java.math.BigDecimal expectedVersion, UploadDocument upload) {
  public VersionDocument(int expectedVersion, UploadDocument upload) {
    this(java.math.BigDecimal.valueOf(expectedVersion), upload);
  }

  public int version() {
    try {
      if (expectedVersion == null) throw new ArithmeticException();
      int v = expectedVersion.intValueExact();
      if (v < 1 || v > 100) throw new ArithmeticException();
      return v;
    } catch (ArithmeticException e) {
      throw new com.atlas.shared.domain.InputValidation(
          java.util.Map.of(
              "expectedVersion", "A whole current version from 1 to 100 is required."));
    }
  }
}
