package com.atlas.modules.documents.application.model;

public record DocumentScanRetry(
    int attempt,
    String actor,
    String reason,
    String idempotencyKey,
    int expectedAttempt,
    String status,
    String scanner,
    java.time.Instant recordedAt,
    String correlationId) {}
