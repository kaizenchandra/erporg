package com.atlas.modules.corehr.domain.model;

import java.time.*;
import java.util.Set;

public record ChangeRequest(
    String id,
    String employeeId,
    String employeeName,
    String field,
    String oldValue,
    String newValue,
    LocalDate effectiveDate,
    String reason,
    String status,
    String requestedBy,
    long employeeVersion,
    Instant recordedAt,
    String decidedBy,
    String comment) {
  public static void validate(
      String field, String value, String reason, LocalDate date, LocalDate today) {
    var errors = new java.util.LinkedHashMap<String, String>();
    if (field == null || !Set.of("phone", "title", "department", "location").contains(field))
      errors.put("field", "Choose a supported field.");
    if (value == null || value.isBlank() || value.length() > 120)
      errors.put("newValue", "New value must contain 1–120 characters.");
    else if ("phone".equals(field) && !value.matches("[+0-9 ()-]{7,30}"))
      errors.put("newValue", "Enter a valid phone number (7–30 characters).");
    if (reason == null || reason.isBlank() || reason.length() > 500)
      errors.put("reason", "A reason of 1–500 characters is required.");
    if (date == null || date.isBefore(today) || date.isAfter(today.plusYears(1)))
      errors.put("effectiveDate", "Effective date must be today or within the next year.");
    if (!errors.isEmpty()) throw new Validation(errors);
  }

  public static class Validation extends IllegalArgumentException {
    private final java.util.Map<String, String> fields;

    public Validation(java.util.Map<String, String> fields) {
      super("Review the highlighted fields.");
      this.fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
    }

    public java.util.Map<String, String> fields() {
      return fields;
    }
  }

  public void ensureDecidable(Actor actor, Employee employee, String decision) {
    if (decision == null || !Set.of("APPROVED", "REJECTED").contains(decision))
      throw new IllegalArgumentException("Choose approve or reject.");
    if (actor.subject().equals(requestedBy)
        || actor.employeeId().equals(employeeId)
        || !(actor.hr()
            || (actor.role().equals("MANAGER") && actor.employeeId().equals(employee.managerId()))))
      throw new Denied();
    if (!status.equals("SUBMITTED")) throw new Conflict("This request has already been decided.");
    if (decision.equals("APPROVED") && employee.version() != employeeVersion)
      throw new Conflict("The employee changed. Reject this request and submit a fresh change.");
  }

  public static class Conflict extends RuntimeException {
    public Conflict(String message) {
      super(message);
    }
  }

  public static class Denied extends RuntimeException {
    public Denied() {
      super("You do not have permission for this action.");
    }
  }
}
