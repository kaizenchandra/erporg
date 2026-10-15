package com.atlas.modules.documents.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ReviseDocumentTemplate(
    BigDecimal expectedVersion,
    String title,
    String body,
    Boolean required,
    LocalDate effectiveDate,
    Boolean retired,
    String reason,
    String idempotencyKey) {}
