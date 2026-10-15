package com.atlas.modules.documents.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RetentionCommand(
    String action,
    BigDecimal expectedVersion,
    LocalDate retainUntil,
    String basis,
    String holdId,
    String caseReference,
    String reason,
    String idempotencyKey) {}
