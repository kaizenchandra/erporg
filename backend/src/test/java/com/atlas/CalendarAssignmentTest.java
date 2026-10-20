package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CalendarAssignmentTest {
  final LocalDate today = LocalDate.of(2026, 10, 19);

  WorkCalendar calendar() {
    return new WorkCalendar(
        "CAL-ASSIGN",
        "Company policy",
        "Europe/London",
        today,
        today.plusDays(30),
        List.of(1, 2, 3, 4, 5),
        List.of(today.plusDays(1)));
  }

  @Test
  void historyQueriesAreBoundedWithoutOffsetOverflow() {
    for (int[] q : new int[][] {{-1, 8}, {100001, 8}, {0, 0}, {0, 51}, {Integer.MAX_VALUE, 50}})
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new com.atlas.modules.corehr.application.model.EmployeeCalendarModels.HistoryQuery(
                  q[0], q[1]));
    new com.atlas.modules.corehr.application.model.EmployeeCalendarModels.HistoryQuery(100000, 50);
  }

  @Test
  void canonicalReferenceAndExplicitUnassignment() {
    CalendarAssignment.shape("");
    CalendarAssignment.shape("CAL-ASSIGN");
    for (var c : List.of("x", "bad-code", "CAL X", "CAL\n"))
      assertThrows(ChangeRequest.Validation.class, () -> CalendarAssignment.shape(c));
    CalendarAssignment.validate("", today, today, today, null, false);
    CalendarAssignment.validate("CAL-ASSIGN", today, today, today, calendar(), false);
  }

  @Test
  void dateEpisodeValidityAndRetirementBoundaries() {
    assertThrows(
        ChangeRequest.Validation.class,
        () -> CalendarAssignment.validate("", today, today.plusDays(1), today, null, false));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            CalendarAssignment.validate(
                "CAL-ASSIGN", today.minusDays(1), today.minusYears(1), today, calendar(), false));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            CalendarAssignment.validate(
                "CAL-ASSIGN", today.plusDays(31), today, today, calendar(), false));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> CalendarAssignment.validate("CAL-ASSIGN", today, today, today, null, false));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> CalendarAssignment.validate("CAL-ASSIGN", today, today, today, calendar(), true));
  }
}
