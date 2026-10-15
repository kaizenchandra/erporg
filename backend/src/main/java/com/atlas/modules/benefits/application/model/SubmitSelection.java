package com.atlas.modules.benefits.application.model;

public record SubmitSelection(
    String planCode,
    java.util.List<com.atlas.modules.benefits.domain.model.Dependant> dependants,
    String reason,
    String idempotencyKey) {}
