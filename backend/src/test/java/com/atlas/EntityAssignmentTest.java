package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class EntityAssignmentTest {
  final LocalDate day = LocalDate.of(2026, 10, 19);

  LegalEntity reference() {
    return new LegalEntity("ENTITY", "Declared reference", "GB", "GBP", "Europe/London", day);
  }

  @Test
  void canonicalAndExplicitUnassignment() {
    EntityAssignment.shape("");
    EntityAssignment.shape("ENTITY-01");
    for (var code : new String[] {null, "bad-code", "X", " A", "ENTITY\n"})
      assertThrows(ChangeRequest.Validation.class, () -> EntityAssignment.shape(code));
    EntityAssignment.validate("", day, day, day, null, false);
    EntityAssignment.validate("ENTITY", day, day, day, reference(), false);
  }

  @Test
  void dateEpisodeAndRetirementRulesAreExplicit() {
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            EntityAssignment.validate(
                "ENTITY", day.minusDays(1), day.minusYears(1), day, reference(), false));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> EntityAssignment.validate("", day, day.plusDays(1), day, null, false));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            EntityAssignment.validate(
                "ENTITY", day.plusYears(1).plusDays(1), day, day, reference(), false));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> EntityAssignment.validate("ENTITY", day, day, day, null, false));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> EntityAssignment.validate("ENTITY", day, day, day, reference(), true));
  }

  @Test
  void privateReferenceNeedsHrInitiationEvenForSelfOrManager() {
    var e =
        Employee.forHire(
            "E003",
            new HireDetails(
                "Synthetic",
                "synthetic@example.test",
                "12345678",
                "Engineer",
                "Engineering",
                "London",
                "E002",
                day));
    assertTrue(ChangeRequest.PRIVATE_FIELDS.contains("legalEntityCode"));
    for (var role : new String[] {"MANAGER", "EMPLOYEE"}) {
      var a = new Actor("actor", "atlas", role.equals("MANAGER") ? "E002" : "E003", "Actor", role);
      assertFalse(a.canChange(e, "legalEntityCode"));
      assertEquals(role.equals("EMPLOYEE"), a.canReadField(e, "legalEntityCode"));
    }
  }
}
