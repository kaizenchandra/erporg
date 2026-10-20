package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class BirthDateTest {
  @Test
  void canonicalDateHasNoInventedAgePolicy() {
    var today = LocalDate.of(2026, 10, 19);
    var start = LocalDate.of(2023, 1, 1);
    assertEquals(LocalDate.of(2000, 2, 29), BirthDate.parse("2000-02-29"));
    BirthDate.validate("0001-01-01", today, start);
    BirthDate.validate("2023-01-01", today, start);
    for (String s :
        List.of(
            "",
            " 2000-02-29",
            "2000-02-29 ",
            "0000-01-01",
            "2023-02-29",
            "2000-2-29",
            "2000-02-29T00:00:00Z",
            "２０２０-01-01")) assertThrows(ChangeRequest.Validation.class, () -> BirthDate.parse(s));
    assertThrows(ChangeRequest.Validation.class, () -> BirthDate.parse(null));
    assertThrows(
        ChangeRequest.Validation.class, () -> BirthDate.validate("2026-10-20", today, start));
    assertThrows(
        ChangeRequest.Validation.class, () -> BirthDate.validate("2023-01-02", today, start));
  }

  @Test
  void unknownFieldsDenyRatherThanBecomingPublic() {
    var e =
        Employee.forHire(
            "E",
            new HireDetails(
                "Person",
                "person@example.test",
                "12345678",
                "Engineer",
                "Engineering",
                "Remote",
                "E2",
                LocalDate.of(2023, 1, 1)));
    for (var role : List.of("HR", "MANAGER", "EMPLOYEE")) {
      var a = new Actor("subject", "tenant", "E", "Person", role);
      assertFalse(a.canReadField(e, "unrecognised-private-field"));
      assertFalse(a.canReadField(e, null));
    }
    assertTrue(ChangeRequest.PRIVATE_FIELDS.contains("birthDate"));
    assertFalse(ChangeRequest.REMOVABLE_PRIVATE_FIELDS.contains("birthDate"));
    assertEquals("", e.birthDate());
  }
}
