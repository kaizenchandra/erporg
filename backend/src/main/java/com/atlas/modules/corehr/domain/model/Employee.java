package com.atlas.modules.corehr.domain.model;

import java.time.LocalDate;

public record Employee(
    String id,
    String name,
    String email,
    String phone,
    String address,
    String emergencyContactName,
    String emergencyContactRelationship,
    String emergencyContactPhone,
    String title,
    String department,
    String location,
    String status,
    String managerId,
    LocalDate startDate,
    long version) {
  public static Employee forHire(String id, HireDetails details) {
    return new Employee(
        id,
        details.name(),
        details.email(),
        details.phone(),
        "",
        "",
        "",
        "",
        details.title(),
        details.department(),
        details.location(),
        "Onboarding",
        details.managerId(),
        details.startDate(),
        0);
  }
}
