package com.atlas.modules.documents.application.model;

import java.time.Instant;

public record DocumentEvidence(
    String code,
    String employeeId,
    String rootId,
    int linkedVersion,
    String actor,
    String reason,
    Instant recordedAt,
    String correlationId,
    int classificationVersion,
    String previousRootId) {
  public DocumentEvidence(
      String code,
      String employeeId,
      String rootId,
      int linkedVersion,
      String actor,
      String reason,
      Instant recordedAt,
      String correlationId) {
    this(
        code, employeeId, rootId, linkedVersion, actor, reason, recordedAt, correlationId, 1, null);
  }
}
