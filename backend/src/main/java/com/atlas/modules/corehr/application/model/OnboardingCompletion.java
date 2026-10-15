package com.atlas.modules.corehr.application.model;

import java.time.*;

public record OnboardingCompletion(
    String hireId,
    String employeeId,
    String actor,
    String reason,
    long inputVersion,
    long newVersion,
    String oldStatus,
    String newStatus,
    LocalDate effectiveDate,
    Instant recordedAt,
    String correlationId) {}
