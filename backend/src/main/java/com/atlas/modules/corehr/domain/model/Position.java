package com.atlas.modules.corehr.domain.model;

import java.time.LocalDate;

public record Position(String code, String title, String departmentCode, LocalDate effectiveFrom) {
  public Position {
    var errors = new java.util.LinkedHashMap<String, String>();
    if (code == null || !code.matches("[A-Z0-9-]{2,30}"))
      errors.put("code", "Position code must contain 2–30 uppercase letters, digits or hyphens.");
    if (title == null || title.isBlank() || title.length() > 120)
      errors.put("title", "Position title must contain 1–120 characters.");
    if (departmentCode == null || !departmentCode.matches("[A-Z0-9-]{2,30}"))
      errors.put("departmentCode", "Choose a department code.");
    if (effectiveFrom == null) errors.put("effectiveFrom", "Position start date is required.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
    title = title.trim();
  }

  public void ensureAssignment(Department department, String employeeDepartment, LocalDate date) {
    if (!department.code().equals(departmentCode) || !department.name().equals(employeeDepartment))
      throw new ChangeRequest.Validation(
          java.util.Map.of(
              "newValue",
              "Position and employee department must match on every affected effective date. Unassign the position before a department transfer."));
    if (effectiveFrom.isAfter(date) || department.effectiveFrom().isAfter(date))
      throw new ChangeRequest.Validation(
          java.util.Map.of(
              "effectiveDate",
              "The position and its department must be effective by the assignment date."));
  }
}
