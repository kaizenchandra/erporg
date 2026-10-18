package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdentityReplacementDomainTest {
  @Test
  void reviewProtectsIndependentEmployeeStateTimeAndApprovalSnapshot() {
    var at = Instant.parse("2026-10-17T10:00:00Z");
    var r =
        new IdentityReplacement(
            "request",
            "E003",
            "old-grant",
            "new-subject",
            "Evidence reviewed",
            2,
            "amelia",
            "E001",
            at,
            "request-correlation",
            "SUBMITTED",
            null,
            null,
            null,
            null,
            null,
            null);
    assertThrows(
        ChangeRequest.Denied.class,
        () -> r.decide("alias", "E001", "APPROVED", "Independent", 2, at, "review"));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> r.decide("target", "E003", "APPROVED", "Independent", 2, at, "review"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> r.decide("olivia", "E005", "APPROVED", "Independent", 3, at, "review"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            r.decide("olivia", "E005", "APPROVED", "Independent", 2, at.minusSeconds(1), "review"));
    var rejected = r.decide("olivia", "E005", "REJECTED", "Reject stale snapshot", 3, at, "review");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> rejected.decide("olivia", "E005", "APPROVED", "Again", 2, at, "review"));
  }
}
