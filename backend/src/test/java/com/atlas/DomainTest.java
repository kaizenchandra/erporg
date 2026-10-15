package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class DomainTest {
  final LocalDate day = LocalDate.of(2026, 10, 14);
  final Employee employee =
      new Employee(
          "E3",
          "Employee",
          "test@example.test",
          "12345678",
          "Synthetic address",
          "Synthetic contact",
          "Sibling",
          "12345678",
          "Engineer",
          "Engineering",
          "London",
          "Active",
          "E2",
          day,
          "",
          2);
  final Actor self = new Actor("employee", "t", "E3", "Employee", "EMPLOYEE");
  final Actor manager = new Actor("manager", "t", "E2", "Manager", "MANAGER");

  ChangeRequest request(long version) {
    return new ChangeRequest(
        "r",
        "E3",
        "Employee",
        "phone",
        "12345678",
        "12345679",
        day,
        "Reason",
        "SUBMITTED",
        "employee",
        version,
        Instant.EPOCH,
        null,
        null);
  }

  @Test
  void dateAndValidationBoundaries() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ChangeRequest.validate(null, "12345678", "Reason", day, day));
    assertThrows(
        IllegalArgumentException.class, () -> request(2).ensureDecidable(manager, employee, null));
    assertDoesNotThrow(
        () -> ChangeRequest.validate("phone", "+44 7700 123456", "Reason", day, day));
    assertDoesNotThrow(
        () -> ChangeRequest.validate("title", "Engineer", "Reason", day.plusYears(1), day));
    assertThrows(
        IllegalArgumentException.class,
        () -> ChangeRequest.validate("phone", "bad", "Reason", day, day));
    assertThrows(
        IllegalArgumentException.class,
        () -> ChangeRequest.validate("phone", "12345678", "Reason", day.minusDays(1), day));
    assertThrows(
        IllegalArgumentException.class,
        () -> ChangeRequest.validate("role", "HR", "Reason", day, day));
  }

  @Test
  void independentReviewerAndOptimisticVersion() {
    assertThrows(
        ChangeRequest.Denied.class, () -> request(2).ensureDecidable(self, employee, "APPROVED"));
    assertDoesNotThrow(() -> request(2).ensureDecidable(manager, employee, "APPROVED"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> request(1).ensureDecidable(manager, employee, "APPROVED"));
    assertDoesNotThrow(() -> request(1).ensureDecidable(manager, employee, "REJECTED"));
  }

  @Test
  void invalidIdentityFailsClosed() {
    assertThrows(ChangeRequest.Denied.class, () -> new Actor("s", "t", "E3", "Name", "ADMIN"));
    assertThrows(ChangeRequest.Denied.class, () -> new Actor("s", "", "E3", "Name", "HR"));
    assertThrows(ChangeRequest.Denied.class, () -> new Actor("s", "t", null, "Name", "HR"));
    assertThrows(ChangeRequest.Denied.class, () -> new Actor("s", "t", "E3", "Name", null));
  }

  @Test
  void validationReturnsAllFieldErrorsWithoutInputEcho() {
    var invalid =
        assertThrows(
            ChangeRequest.Validation.class,
            () ->
                ChangeRequest.validate(
                    "phone", "private-invalid-value", " ", day.minusDays(1), day));
    assertEquals(
        java.util.Set.of("newValue", "reason", "effectiveDate"), invalid.fields().keySet());
    assertFalse(invalid.fields().toString().contains("private-invalid-value"));
    assertThrows(UnsupportedOperationException.class, () -> invalid.fields().put("extra", "bad"));
  }

  @Test
  void removalOnlyAllowsExplicitEmptyOptionalPrivateFields() {
    for (String field : ChangeRequest.PRIVATE_FIELDS) {
      assertDoesNotThrow(
          () -> ChangeRequest.validate(field, "", "Remove obsolete value", day, day));
      assertThrows(
          ChangeRequest.Validation.class,
          () -> ChangeRequest.validate(field, null, "Reason", day, day));
      assertThrows(
          ChangeRequest.Validation.class,
          () -> ChangeRequest.validate(field, "   ", "Reason", day, day));
    }
    for (String field : java.util.Set.of("phone", "title", "department", "location"))
      assertThrows(
          ChangeRequest.Validation.class,
          () -> ChangeRequest.validate(field, "", "Reason", day, day));
  }

  @Test
  void positionRejectsInvalidFieldsWithoutEchoingInput() {
    var invalid =
        assertThrows(
            ChangeRequest.Validation.class, () -> new Position("lowercase", " ", null, null));
    assertEquals(
        java.util.Set.of("code", "title", "departmentCode", "effectiveFrom"),
        invalid.fields().keySet());
    assertFalse(invalid.fields().toString().contains("lowercase"));
    assertEquals("Analyst", new Position("ROLE-1", " Analyst ", "DEPT", day).title());
  }

  @Test
  void positionAssignmentChecksDepartmentAndEmploymentDates() {
    var department = new Department("DEPT", "Engineering", null, "CC-TEST", day);
    var position = new Position("ROLE", "Engineer", "DEPT", day);
    assertDoesNotThrow(() -> position.ensureAssignment(department, "Engineering", day));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> position.ensureAssignment(department, "Finance", day));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> position.ensureAssignment(department, "Engineering", day.minusDays(1)));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> employee.ensurePositionEffectiveDate(day.minusDays(1)));
    assertDoesNotThrow(
        () -> ChangeRequest.validate("positionCode", "", "Unassign position", day, day));
    assertFalse(self.canChange(employee, "positionCode"));
    assertTrue(manager.canChange(employee, "positionCode"));
  }

  @Test
  void reportingHierarchyRejectsCyclesAndDateBoundaries() {
    var links =
        java.util.List.of(
            new ReportingLink("A", null, day),
            new ReportingLink("B", "A", day),
            new ReportingLink("C", "B", day));
    assertDoesNotThrow(
        () ->
            com.atlas.modules.corehr.domain.service.ReportingHierarchy.validate(
                links, "C", "A", day));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            com.atlas.modules.corehr.domain.service.ReportingHierarchy.validate(
                links, "A", "C", day));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            com.atlas.modules.corehr.domain.service.ReportingHierarchy.validate(
                links, "B", "B", day));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            com.atlas.modules.corehr.domain.service.ReportingHierarchy.validate(
                links, "C", "A", day.minusDays(1)));
  }

  @Test
  void displayNamesAreUnicodeAndEmployeeOwnedButNotBlankOrControlText() {
    assertDoesNotThrow(() -> ChangeRequest.validate("name", "Zoë 李", "Display name", day, day));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> ChangeRequest.validate("name", " ", "Reason", day, day));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> ChangeRequest.validate("name", "New\nName", "Reason", day, day));
    assertTrue(self.canChange(employee, "name"));
    assertFalse(manager.canChange(employee, "name"));
  }

  @Test
  void fieldAndRecordPolicy() {
    assertTrue(self.canChange(employee, "phone"));
    assertFalse(self.canChange(employee, "title"));
    assertTrue(manager.canChange(employee, "title"));
    assertFalse(manager.canReadField(employee, "address"));
    assertFalse(manager.canChange(employee, "emergencyContactName"));
    assertTrue(self.canChange(employee, "address"));
    assertTrue(self.canChange(employee, "emergencyContactPhone"));
    assertFalse(new Actor("other", "t", "E9", "Other", "EMPLOYEE").canRead(employee));
  }
}
