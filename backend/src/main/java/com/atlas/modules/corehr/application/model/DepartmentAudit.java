package com.atlas.modules.corehr.application.model;

import java.time.Instant;
import java.time.LocalDate;

public record DepartmentAudit(
    String code,
    String actor,
    String reason,
    Instant recordedAt,
    LocalDate effectiveFrom,
    String correlationId) {}
