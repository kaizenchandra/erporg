package com.atlas.modules.corehr.application.model;

public record CompleteOnboarding(String reason, Long version, String idempotencyKey) {}
