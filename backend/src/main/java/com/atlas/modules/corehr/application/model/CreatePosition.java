package com.atlas.modules.corehr.application.model;

import java.time.LocalDate;

public record CreatePosition(
    String code,
    String title,
    String departmentCode,
    LocalDate effectiveFrom,
    String reason,
    String idempotencyKey) {}
