package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PersonalEmailTest {
  @Test
  void boundedDotAtomVocabularyAndEmptyRemoval() {
    for (var value :
        List.of(
            "",
            "Person.Name+tag@Example.test",
            "o'hara@sub.example.test",
            "x@xn--bcher-kva.test",
            "a".repeat(64) + "@example.test")) assertTrue(PersonalEmail.valid(value), value);
    for (var value :
        List.of(
            "a",
            "a@localhost",
            "a..b@example.test",
            ".a@example.test",
            "a.@example.test",
            "a b@example.test",
            "a@-example.test",
            "a@example-.test",
            "a@" + "x".repeat(64) + ".test",
            "a".repeat(65) + "@example.test",
            "a@例.test",
            "a@b.test\n",
            " a@b.test",
            "a".repeat(64) + "@" + "b".repeat(51) + ".test"))
      assertFalse(PersonalEmail.valid(value), value);
    assertFalse(PersonalEmail.valid(null));
  }

  @Test
  void privateRemovableFieldUsesSharedEffectiveDateValidation() {
    assertTrue(ChangeRequest.PRIVATE_FIELDS.contains("personalEmail"));
    assertTrue(ChangeRequest.REMOVABLE_PRIVATE_FIELDS.contains("personalEmail"));
    ChangeRequest.validateShape(
        "personalEmail", "", "Explicit removal", LocalDate.of(2026, 10, 19));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            ChangeRequest.validateShape(
                "personalEmail", null, "Missing value", LocalDate.of(2026, 10, 19)));
    PersonalEmail.validate(" Person.Name@Example.test ");
  }
}
