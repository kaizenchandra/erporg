package com.atlas.modules.corehr.application.model;

import java.time.LocalDate;

public record PositionListing(
    String code,
    String title,
    String departmentCode,
    String departmentName,
    LocalDate effectiveFrom) {}
