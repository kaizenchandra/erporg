package com.atlas.modules.corehr.domain.model;

import java.time.LocalDate;

public record Employee(
    String id,
    String name,
    String email,
    String phone,
    String title,
    String department,
    String location,
    String status,
    String managerId,
    LocalDate startDate,
    long version) {}
