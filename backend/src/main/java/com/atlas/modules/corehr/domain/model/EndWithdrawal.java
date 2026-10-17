package com.atlas.modules.corehr.domain.model;

import java.time.Instant;

/** Immutable submission and optional independent decision, projected together for HR. */
public record EndWithdrawal(
    String id,
    String requestId,
    String employeeId,
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
