package com.atlas.modules.benefits.application.model;

public record DecideSelection(Long version, String outcome, String reason, String idempotencyKey) {}
