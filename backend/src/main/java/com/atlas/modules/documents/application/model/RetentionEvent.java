package com.atlas.modules.documents.application.model;

import java.time.*;

public record RetentionEvent(
    String rootId,
    int version,
    String action,
    LocalDate retainUntil,
    String basis,
    String holdId,
    String caseReference,
    String actor,
    String actorEmployeeId,
    String reason,
    Instant recordedAt,
    String correlationId) {}
