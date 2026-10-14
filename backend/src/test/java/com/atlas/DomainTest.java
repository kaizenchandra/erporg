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
          "Engineer",
          "Engineering",
          "London",
          "Active",
          "E2",
          day,
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
  void fieldAndRecordPolicy() {
    assertTrue(self.canChange(employee, "phone"));
    assertFalse(self.canChange(employee, "title"));
    assertTrue(manager.canChange(employee, "title"));
    assertFalse(new Actor("other", "t", "E9", "Other", "EMPLOYEE").canRead(employee));
  }
}
