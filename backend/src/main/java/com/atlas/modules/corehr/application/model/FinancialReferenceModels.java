package com.atlas.modules.corehr.application.model;

import java.time.LocalDate;

public final class FinancialReferenceModels {
  private FinancialReferenceModels() {}

  public record Entity(
      String code, String currency, LocalDate effectiveFrom, LocalDate retiredOn) {}

  public record Worker(String id, String status, LocalDate start, String entityCode) {}
}
