package com.atlas.modules.documents.application.model;

import java.time.LocalDate;
import java.util.List;

public record RetentionReview(
    String rootId,
    String employeeId,
    int version,
    LocalDate asOf,
    String state,
    LocalDate retainUntil,
    String basis,
    List<Hold> holds,
    List<RetentionEvent> history,
    boolean deletionAvailable) {
  public record Hold(
      String id, String caseReference, String placedBy, String placedEmployeeId, boolean active) {}
}
