package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CalendarRevisionTest {
  WorkCalendar base() {
    return new WorkCalendar(
        "CAL",
        "Company",
        "Europe/London",
        LocalDate.of(2028, 2, 1),
        LocalDate.of(2028, 3, 31),
        List.of(1, 2, 3, 4, 5),
        List.of());
  }

  @Test
  void datedSelectionPreservesEarlierDaysAndDoesNotApplyBeforeFutureStart() {
    var b = base();
    var date = LocalDate.of(2028, 2, 29);
    var p =
        new WorkCalendar(
            b.code(),
            b.name(),
            b.timeZone(),
            b.effectiveFrom(),
            b.effectiveUntil(),
            List.of(1, 2, 3, 4, 5, 6, 7),
            List.of(date));
    var r = new CalendarRevision(1, date, p);
    r.validateNew(null, LocalDate.of(2028, 2, 1));
    assertEquals(0, CalendarRevision.select(b, List.of(r), date.minusDays(1)).version());
    assertEquals(
        0, CalendarRevision.select(b, List.of(r), b.effectiveFrom().minusDays(1)).version());
    assertEquals(
        "COMPANY_CLOSURE",
        CalendarRevision.select(b, List.of(r), date).policy().classification(date));
    assertEquals(1, CalendarRevision.select(b, List.of(r), date).version());
  }

  @Test
  void newAmendmentsRequireSequenceFutureDateWindowAndFutureClosures() {
    var b = base();
    var first = new CalendarRevision(1, b.effectiveFrom(), b);
    first.validateNew(null, b.effectiveFrom());
    assertThrows(
        ChangeRequest.Validation.class,
        () -> first.validateNew(first, b.effectiveFrom().plusDays(1)));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            new CalendarRevision(3, b.effectiveFrom().plusDays(1), b)
                .validateNew(first, b.effectiveFrom()));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> new CalendarRevision(2, b.effectiveFrom(), b).validateNew(first, b.effectiveFrom()));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> new CalendarRevision(1, b.effectiveUntil().plusDays(1), b));
    assertThrows(
        ChangeRequest.Validation.class, () -> new CalendarRevision(21, b.effectiveFrom(), b));
    var oldClosure =
        new WorkCalendar(
            b.code(),
            b.name(),
            b.timeZone(),
            b.effectiveFrom(),
            b.effectiveUntil(),
            b.workingDays(),
            List.of(b.effectiveFrom()));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> new CalendarRevision(1, b.effectiveFrom().plusDays(1), oldClosure));
  }
}
