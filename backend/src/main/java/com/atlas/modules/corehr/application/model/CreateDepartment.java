package com.atlas.modules.corehr.application.model;

import java.time.LocalDate;

public record CreateDepartment(
    String code,
    String name,
    String parentCode,
    String costCentre,
    LocalDate effectiveFrom,
    String reason,
    String idempotencyKey) {}
