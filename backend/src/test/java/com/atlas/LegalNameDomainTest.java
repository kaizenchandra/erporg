package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.ChangeRequest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LegalNameDomainTest {
  @Test
  void supportsUnicodeWithoutInferringNamesOrAllowingEmptyRemovalOrControls() {
    var date = LocalDate.of(2026, 10, 17);
    assertDoesNotThrow(
        () -> ChangeRequest.validateShape("legalName", "李 Zoë O’Neill", "Supplied name", date));
    for (var value :
        java.util.List.of("", " ", "Invalid\nName", "Invalid\u0001Name", "x".repeat(121)))
      assertThrows(
          ChangeRequest.Validation.class,
          () -> ChangeRequest.validateShape("legalName", value, "Supplied name", date));
    assertTrue(ChangeRequest.PRIVATE_FIELDS.contains("legalName"));
  }
}
