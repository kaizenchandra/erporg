package com.atlas.modules.corehr.domain.model;

import java.time.Instant;

public record RehireActivation(
    String id,
    String rehireId,
    String employeeId,
    String subject,
    String reason,
    long inputVersion,
    String actor,
    String actorEmployeeId,
    Instant recordedAt,
    String correlationId,
    String status,
    String reviewer,
    String reviewerEmployeeId,
    String comment,
    Long reviewVersion,
    Instant reviewedAt,
    String reviewCorrelationId) {}
