package com.atlas.modules.benefits.application.model;

import java.time.LocalDate;

public record PlanView(
    String code,
    String name,
    String currency,
    String monthlyContribution,
    int minimumServiceDays,
    LocalDate effectiveFrom,
    boolean eligible,
    String explanation,
    String ruleVersion,
    String mode) {}
