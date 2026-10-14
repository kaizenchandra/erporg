package com.atlas.modules.corehr.application.model;

import java.time.*;

public record AuditEntry(
    String action,
    String field,
    String oldValue,
    String newValue,
    String reason,
    String actor,
    Instant recordedAt,
    String correlationId,
    LocalDate effectiveDate) {}
