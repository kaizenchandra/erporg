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
  public static final Set<String> PRIVATE_FIELDS =
      Set.of(
          "address",
          "emergencyContactName",
          "emergencyContactRelationship",
          "emergencyContactPhone");
  public static final Set<String> FIELDS =
      Set.of(
          "phone",
          "name",
          "title",
          "department",
          "location",
          "positionCode",
          "managerId",
          "address",
          "emergencyContactName",
          "emergencyContactRelationship",
          "emergencyContactPhone");

  public static void validate(
      String field, String value, String reason, LocalDate date, LocalDate today) {
    var errors = new java.util.LinkedHashMap<String, String>();
    try {
      validateShape(field, value, reason, date);
    } catch (Validation e) {
      errors.putAll(e.fields());
    }
    if (date != null)
      try {
        validateDate(date, today);
      } catch (Validation e) {
        errors.putAll(e.fields());
      }
    if (!errors.isEmpty()) throw new Validation(errors);
  }

  public static void validateShape(String field, String value, String reason, LocalDate date) {
    var errors = new java.util.LinkedHashMap<String, String>();
    if (field == null || !FIELDS.contains(field)) errors.put("field", "Choose a supported field.");
    boolean removal =
        field != null
            && (PRIVATE_FIELDS.contains(field) || field.equals("positionCode"))
            && "".equals(value);
    if (value == null || (!removal && value.isBlank()) || value.length() > 120)
      errors.put("newValue", "New value must contain 1–120 characters.");
    else if (!removal
        && ("phone".equals(field) || "emergencyContactPhone".equals(field))
        && !value.matches("[+0-9 ()-]{7,30}"))
      errors.put("newValue", "Enter a valid phone number (7–30 characters).");
    if ("name".equals(field)
        && value != null
        && value.codePoints().anyMatch(Character::isISOControl))
      errors.put("newValue", "Display names cannot contain control characters.");
    if (reason == null || reason.isBlank() || reason.length() > 500)
      errors.put("reason", "A reason of 1–500 characters is required.");
    if (date == null) errors.put("effectiveDate", "Effective date is required.");
    if (!errors.isEmpty()) throw new Validation(errors);
  }

  public static void validateDate(LocalDate date, LocalDate today) {
    if (date.isBefore(today) || date.isAfter(today.plusYears(1)))
      throw new Validation(
          java.util.Map.of(
              "effectiveDate", "Effective date must be today or within the next year."));
  }

  public void ensureWithdrawable(Actor actor) {
    if (!requestedBy.equals(actor.subject())) throw new Denied();
    if (!status.equals("SUBMITTED")) throw new Conflict("Only a pending request can be withdrawn.");
  }

  public static class Validation extends com.atlas.shared.domain.InputValidation {
    public Validation(java.util.Map<String, String> fields) {
      super(fields);
    }
  }

  public void ensureDecidable(Actor actor, Employee employee, String decision) {
    if (decision == null || !Set.of("APPROVED", "REJECTED").contains(decision))
      throw new IllegalArgumentException("Choose approve or reject.");
    if (!actor.canReadField(employee, field)) throw new Denied();
    if (actor.subject().equals(requestedBy)
        || actor.employeeId().equals(employeeId)
        || !(actor.hr()
            || (actor.role().equals("MANAGER") && actor.employeeId().equals(employee.managerId()))))
      throw new Denied();
    if (!status.equals("SUBMITTED")) throw new Conflict("This request has already been decided.");
    if (decision.equals("APPROVED") && employee.version() != employeeVersion)
      throw new Conflict("The employee changed. Reject this request and submit a fresh change.");
  }

  public static class Conflict extends com.atlas.shared.domain.CommandConflict {
    public Conflict(String message) {
      super(message);
    }
  }

  public static class Denied extends com.atlas.shared.domain.AccessDenied {
    public Denied() {
      super();
    }
  }
}
