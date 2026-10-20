package com.atlas.modules.benefits.application.model;

import java.math.BigDecimal;

public record WithdrawSelection(BigDecimal version, String reason, String idempotencyKey) {}
