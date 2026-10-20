package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkCalendarTest {
  WorkCalendar calendar() {
    return new WorkCalendar(
        "DEMO-WORK",
        "Company calendar 李",
        "Europe/London",
        LocalDate.of(2028, 2, 1),
        LocalDate.of(2028, 3, 1),
        List.of(5, 4, 3, 2, 1),
        List.of(LocalDate.of(2028, 2, 29), LocalDate.of(2028, 2, 26)));
  }

  @Test
  void classifiesLocalDaysAndClosurePrecedenceAcrossLeapAndWeekend() {
    var c = calendar();
    assertEquals(List.of(1, 2, 3, 4, 5), c.workingDays());
    assertEquals("COMPANY_CLOSURE", c.classification(LocalDate.of(2028, 2, 29)));
    assertEquals("COMPANY_CLOSURE", c.classification(LocalDate.of(2028, 2, 26)));
    assertEquals("NON_WORKING_WEEKDAY", c.classification(LocalDate.of(2028, 2, 27)));
    assertEquals("WORKING_DAY", c.classification(LocalDate.of(2028, 2, 28)));
    assertThrows(ChangeRequest.Validation.class, () -> c.classification(LocalDate.of(2028, 1, 31)));
    assertThrows(UnsupportedOperationException.class, () -> c.workingDays().add(7));
  }

  @Test
  void validatesExactDatesVocabularyWindowAndSets() {
    for (var s : List.of("0000-01-01", "2027-02-29", "2028-2-29", "2028-02-29T00:00:00Z"))
      assertThrows(ChangeRequest.Validation.class, () -> WorkCalendar.date(s, "date"));
    assertEquals(LocalDate.of(2028, 2, 29), WorkCalendar.date("2028-02-29", "date"));
    var c = calendar();
    c.validateNew(LocalDate.of(2028, 1, 31));
    assertThrows(ChangeRequest.Validation.class, () -> c.validateNew(LocalDate.of(2028, 2, 2)));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            new WorkCalendar(
                "OK",
                "Valid",
                "Europe/London",
                c.effectiveFrom(),
                c.effectiveFrom().plusYears(1),
                List.of(1),
                List.of()));
    for (var days : List.of(List.<Integer>of(), List.of(1, 1), List.of(0), List.of(8)))
      assertThrows(
          ChangeRequest.Validation.class,
          () ->
              new WorkCalendar(
                  "OK",
                  "Valid",
                  "Europe/London",
                  c.effectiveFrom(),
                  c.effectiveUntil(),
                  days,
                  List.of()));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            new WorkCalendar(
                "OK",
                "Valid",
                "Europe/London",
                c.effectiveFrom(),
                c.effectiveUntil(),
                List.of(1),
                List.of(c.effectiveFrom(), c.effectiveFrom())));
  }
}
