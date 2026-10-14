package com.atlas.modules.corehr.application.model;

import java.time.*;

public record HireAudit(
    String oldStatus,
    String newStatus,
    String actor,
    String reason,
    Instant recordedAt,
    LocalDate effectiveDate,
    String correlationId) {}
