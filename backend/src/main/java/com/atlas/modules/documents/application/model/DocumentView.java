package com.atlas.modules.documents.application.model;

public record DocumentView(
    String id,
    String employeeId,
    String filename,
    String mediaType,
    int bytes,
    String status,
    String scanner,
    String reason,
    java.time.Instant recordedAt,
    int scanAttempts,
    String rootId,
    int version) {}
