package com.atlas.modules.corehr.application.model;

import java.time.Instant;
import java.time.LocalDate;

public record PositionAudit(
    String code,
    String actor,
    String reason,
    Instant recordedAt,
    LocalDate effectiveFrom,
    String correlationId) {}
