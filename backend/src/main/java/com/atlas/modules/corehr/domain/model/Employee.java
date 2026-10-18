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
    String positionCode,
    String legalName,
    long version) {
  public void ensurePositionEffectiveDate(LocalDate date) {
    if (date.isBefore(startDate))
      throw new ChangeRequest.Validation(
          java.util.Map.of(
              "effectiveDate", "A position assignment cannot precede employment start."));
  }

  public Employee completeOnboarding(LocalDate today) {
    if (!status.equals("Onboarding"))
      throw new ChangeRequest.Conflict("Only an onboarding employee can complete this transition.");
    if (today.isBefore(startDate))
      throw new ChangeRequest.Conflict(
          "The employment start date has not been reached. Complete onboarding on or after "
              + startDate
              + ".");
    return new Employee(
        id,
        name,
        email,
        phone,
        address,
        emergencyContactName,
        emergencyContactRelationship,
        emergencyContactPhone,
        title,
        department,
        location,
        "Active",
        managerId,
        startDate,
        positionCode,
        legalName,
        version + 1);
  }

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
        "",
        "",
        0);
  }
}
