package com.atlas.modules.benefits.application.model;

import java.time.LocalDate;

public record BenefitContext(
    String subject,
    String tenant,
    String employeeId,
    String role,
    String employmentStatus,
    LocalDate employmentStart) {}
