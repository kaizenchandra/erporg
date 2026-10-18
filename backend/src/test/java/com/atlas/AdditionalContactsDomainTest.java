package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdditionalContactsDomainTest {
  @Test
  void validatesBoundedContactsAndImmutableWholeList() {
    var c = new EmergencyContact(" Zoë 李 ", " Sibling ", "+1 202 555 0101");
    assertEquals("Zoë 李", c.name());
    for (var phone : List.of("", "letters", "-------", "1234"))
      assertThrows(
          ChangeRequest.Validation.class, () -> new EmergencyContact("Name", "Sibling", phone));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> new EmergencyContact("Bad\nName", "Sibling", "12345678"));
    assertThrows(ChangeRequest.Validation.class, () -> EmergencyContact.snapshot(List.of(c, c)));
    assertThrows(
        ChangeRequest.Validation.class, () -> EmergencyContact.snapshot(Collections.nCopies(5, c)));
    var source = new ArrayList<>(List.of(c));
    var snapshot = EmergencyContact.snapshot(source);
    source.clear();
    assertEquals(1, snapshot.size());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
    assertTrue(EmergencyContact.snapshot(List.of()).isEmpty());
  }

  @Test
  void reviewerCannotBeRequesterOrSameEmployeeAlias() {
    var r =
        new ContactRequest(
            "r",
            "target",
            List.of(),
            List.of(),
            java.time.LocalDate.of(2026, 10, 18),
            "reason",
            "SUBMITTED",
            "hr",
            "reviewer-one",
            0,
            java.time.Instant.EPOCH,
            "c",
            0,
            null,
            null,
            null,
            null);
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            r.requireReviewer(
                new Actor("hr-alias", "t", "reviewer-one", "Alias", "HR"), "APPROVED", 0));
    assertDoesNotThrow(
        () ->
            r.requireReviewer(
                new Actor("other", "t", "reviewer-two", "Other", "HR"), "APPROVED", 0));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            r.requireReviewer(
                new Actor("other", "t", "reviewer-two", "Other", "HR"), "APPROVED", 1));
  }
}
