package com.atlas.modules.benefits.application.model;

import java.time.LocalDate;

public record CreatePlan(
    String code,
    String name,
    String currency,
    String monthlyContribution,
    String minimumServiceDays,
    LocalDate effectiveFrom,
    String reason,
    String idempotencyKey) {}
