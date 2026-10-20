package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CalendarRetirementTest {
  WorkCalendar base() {
    return new WorkCalendar(
        "CAL",
        "Company",
        "Europe/London",
        LocalDate.of(2028, 2, 1),
        LocalDate.of(2028, 3, 31),
        List.of(1),
        List.of());
  }

  @Test
  void retirementIsTerminalAtExactLocalDateAndFollowsLatestAmendment() {
    var b = base();
    var date = LocalDate.of(2028, 2, 29);
    var r = new CalendarRetirement("CAL", 0, date);
    r.validateNew(b, null, b.effectiveFrom());
    assertFalse(r.applies(date.minusDays(1)));
    assertTrue(r.applies(date));
    assertTrue(r.applies(date.plusDays(1)));
    var revision = new CalendarRevision(1, date.minusDays(1), b);
    new CalendarRetirement("CAL", 1, date).validateNew(b, revision, b.effectiveFrom());
    assertThrows(ChangeRequest.Conflict.class, () -> r.validateNew(b, revision, b.effectiveFrom()));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            new CalendarRetirement("CAL", 1, revision.effectiveDate())
                .validateNew(b, revision, b.effectiveFrom()));
  }

  @Test
  void retirementRejectsBackdatingRangeAndWrongReference() {
    var b = base();
    for (var date : List.of(b.effectiveFrom().minusDays(1), b.effectiveUntil().plusDays(1)))
      assertThrows(
          ChangeRequest.Validation.class,
          () -> new CalendarRetirement("CAL", 0, date).validateNew(b, null, b.effectiveFrom()));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            new CalendarRetirement("CAL", 0, b.effectiveFrom())
                .validateNew(b, null, b.effectiveFrom().plusDays(1)));
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            new CalendarRetirement("OTHER", 0, b.effectiveFrom())
                .validateNew(b, null, b.effectiveFrom()));
    assertThrows(
        ChangeRequest.Validation.class, () -> new CalendarRetirement("CAL", -1, b.effectiveFrom()));
  }
}
