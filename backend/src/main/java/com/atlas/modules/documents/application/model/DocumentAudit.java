package com.atlas.modules.documents.application.model;

public record DocumentAudit(
    String action,
    String actor,
    java.time.Instant recordedAt,
    String reason,
    String correlationId) {}
