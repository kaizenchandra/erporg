package com.atlas.modules.documents.application.model;

import java.time.LocalDate;
import java.util.List;

public record DocumentCompleteness(String employeeId, LocalDate asOf, List<Item> items) {
  public record Item(
      String code,
      String title,
      boolean required,
      LocalDate effectiveDate,
      String state,
      String rootId,
      String documentId,
      Integer version,
      String scanStatus) {}
}
