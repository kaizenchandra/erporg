package com.atlas.modules.corehr.application.model;

import java.time.*;

public record ResignationAudit(
    String oldStatus,
    String newStatus,
    String actor,
    String reason,
    LocalDate effectiveDate,
    Instant recordedAt,
    String correlationId) {}
