package com.atlas.modules.benefits.application.model;

public record SelectionView(
    String id,
    String employeeId,
    String planCode,
    String planName,
    String currency,
    String monthlyContribution,
    String ruleVersion,
    java.time.LocalDate effectiveDate,
    String requestedBy,
    String reason,
    String idempotencyKey,
    String status,
    long version,
    String decidedBy,
    String decisionKey,
    String decisionReason,
    java.util.List<com.atlas.modules.benefits.domain.model.Dependant> dependants,
    java.util.List<SelectionAudit> history,
    String handoffStatus) {}
