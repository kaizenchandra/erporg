package com.atlas.modules.corehr.domain.model;

import java.time.*;

/** Original and replacement dates remain separate from the recorded review instant. */
public record EndReschedule(
    String id,
    String requestId,
    String employeeId,
    LocalDate lastWorkingDate,
    LocalDate priorInactiveDate,
    String oldStatus,
    String actor,
    String actorEmployeeId,
    String reason,
    long inputVersion,
    Instant recordedAt,
    String correlationId,
    String status,
    String reviewer,
    String reviewerEmployeeId,
    String comment,
    Long reviewVersion,
    Instant reviewedAt,
    String reviewCorrelationId) {}
