package com.atlas.modules.benefits.application.model;

public record SelectionAudit(
    String action,
    String actor,
    String reason,
    java.time.Instant recordedAt,
    String correlationId) {}
