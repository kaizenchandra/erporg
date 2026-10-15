package com.atlas.modules.documents.application.model;

import java.math.BigDecimal;

public record LinkDocumentEvidence(
    String documentId, BigDecimal expectedVersion, String reason, String idempotencyKey) {}
