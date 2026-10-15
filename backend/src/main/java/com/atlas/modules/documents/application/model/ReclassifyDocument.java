package com.atlas.modules.documents.application.model;

import java.math.BigDecimal;

public record ReclassifyDocument(
    String employeeId,
    String documentId,
    BigDecimal expectedVersion,
    BigDecimal expectedClassificationVersion,
    String reason,
    String idempotencyKey) {}
