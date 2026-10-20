package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import com.atlas.modules.corehr.domain.model.LegalEntityRetirement.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class LegalEntityRetirementTest {
  LocalDate today = LocalDate.of(2026, 10, 19);

  @Test
  void dateAndTextRulesAreBoundedAndNotStatutory() {
    assertDoesNotThrow(() -> LegalEntityRetirement.dates(today, today, today));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> LegalEntityRetirement.dates(today.minusDays(1), today, today));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> LegalEntityRetirement.dates(today.plusYears(1).plusDays(1), today, today));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> LegalEntityRetirement.text("Invalid\nreason", "reason"));
  }

  @Test
  void independentReviewUsesSubjectAndEmployeeAndExpiredApprovalCanBeRejected() {
    var p =
        new Proposal("id", "CODE", 1, 0, today, "Reason", "author", "E001", Instant.EPOCH, "corr");
    assertThrows(
        ChangeRequest.Denied.class, () -> LegalEntityRetirement.independent(p, "author", "E005"));
    assertThrows(
        ChangeRequest.Denied.class, () -> LegalEntityRetirement.independent(p, "alias", "E001"));
    assertDoesNotThrow(() -> LegalEntityRetirement.independent(p, "other", "E005"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> LegalEntityRetirement.decision("APPROVED", "Review", today, today.plusDays(1)));
    assertDoesNotThrow(
        () ->
            LegalEntityRetirement.decision(
                "REJECTED", "Expired proposal", today, today.plusDays(1)));
  }
}
