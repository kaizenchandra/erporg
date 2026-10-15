package com.atlas.modules.benefits.application.model;

import java.time.*;

public record PlanAudit(
    String code,
    String actor,
    String reason,
    Instant recordedAt,
    LocalDate effectiveFrom,
    String correlationId) {}
