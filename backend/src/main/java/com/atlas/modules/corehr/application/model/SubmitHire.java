package com.atlas.modules.corehr.application.model;

import java.time.LocalDate;

public record SubmitHire(
    String name,
    String email,
    String phone,
    String title,
    String department,
    String location,
    String managerId,
    LocalDate startDate,
    String reason,
    String idempotencyKey) {}
