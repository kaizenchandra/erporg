package com.atlas.modules.corehr.application.model;

import java.time.Instant;

public record Withdrawal(
    String requestId,
    String actor,
    String reason,
    String idempotencyKey,
    Instant recordedAt,
    String correlationId) {}
