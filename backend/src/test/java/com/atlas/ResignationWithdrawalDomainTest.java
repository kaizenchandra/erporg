package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class ResignationWithdrawalDomainTest {
  @Test
  void onlyOriginalPendingRequesterMayWithdrawAtOrAfterSubmission() {
    var now = Instant.parse("2026-10-17T00:00:00Z");
    var r =
        new Resignation(
            "id",
            "E003",
            LocalDate.of(2026, 11, 1),
            "Original",
            "SUBMITTED",
            "sophia",
            5,
            now,
            0,
            null,
            null,
            null,
            null);
    var a = new Actor("sophia", "atlas", "E003", "Synthetic", "EMPLOYEE");
    assertEquals(
        "Reason", ResignationWithdrawal.withdraw(r, a, " Reason ", 0, now, "correlation").reason());
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            ResignationWithdrawal.withdraw(
                r,
                new Actor("alias", "atlas", "E003", "Alias", "HR"),
                "Reason",
                0,
                now,
                "correlation"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> ResignationWithdrawal.withdraw(r, a, "Reason", 1, now, "correlation"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> ResignationWithdrawal.withdraw(r, a, "Reason", 0, now.minusNanos(1), "correlation"));
  }
}
