package com.atlas.modules.documents.application.model;

import java.time.LocalDate;

public record CreateDocumentTemplate(
    String code,
    String title,
    String body,
    Boolean required,
    LocalDate effectiveDate,
    String reason,
    String idempotencyKey) {}
