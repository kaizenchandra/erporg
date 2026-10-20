package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.timekeeping.domain.model.TimePolicy;
import com.atlas.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;

class TimePolicyTest {
  @Test
  void exactMinutesAndRevisionBoundaries() {
    assertEquals(75, TimePolicy.minutes(new BigDecimal("1.25")));
    assertEquals(3, TimePolicy.minutes(new BigDecimal("0.05")));
  }

  @Test
  void invalidMinutesAndVersions() {
    assertEquals(1, TimePolicy.wholeMinutes(new BigDecimal("1.0")));
    assertThrows(InputValidation.class, () -> TimePolicy.wholeMinutes(new BigDecimal("0.5")));
    for (var n : new String[] {"-1", "24.01", "0.001", "1e40"})
      assertThrows(InputValidation.class, () -> TimePolicy.minutes(new BigDecimal(n)));
    for (var n : new String[] {"-1", "0.5", "9223372036854775808"})
      assertThrows(InputValidation.class, () -> TimePolicy.revision(new BigDecimal(n)));
    assertEquals(Long.MAX_VALUE, TimePolicy.revision(new BigDecimal("9223372036854775807")));
    assertEquals(0, TimePolicy.revision(new BigDecimal("0.0")));
  }

  @Test
  void dstGapsAndFoldsAreExplicit() {
    assertThrows(
        InputValidation.class,
        () -> TimePolicy.timestamp("2026-10-20T09:00:00.1234567Z", ZoneId.of("Etc/UTC"), "start"));
    var z = ZoneId.of("Europe/London");
    assertThrows(
        InputValidation.class, () -> TimePolicy.timestamp("2026-03-29T01:30:00+00:00", z, "start"));
    var a = TimePolicy.timestamp("2026-10-25T01:30:00+01:00", z, "start");
    var b = TimePolicy.timestamp("2026-10-25T01:30:00+00:00", z, "end");
    assertEquals(3600, Duration.between(a, b).getSeconds());
    assertThrows(
        InputValidation.class, () -> TimePolicy.timestamp("2026-10-20T09:00:00+05:30", z, "start"));
  }

  @Test
  void intervalsAndTransitions() {
    var now = Instant.parse("2026-10-20T12:00:00Z");
    TimePolicy.interval(now.minusSeconds(3600), now, now, LocalDate.of(2020, 1, 1), ZoneOffset.UTC);
    assertThrows(
        InputValidation.class,
        () -> TimePolicy.interval(now, now, now, LocalDate.of(2020, 1, 1), ZoneOffset.UTC));
    assertThrows(
        InputValidation.class,
        () ->
            TimePolicy.interval(
                now.minusSeconds(90000), now, now, LocalDate.of(2020, 1, 1), ZoneOffset.UTC));
    assertEquals("SUBMITTED", TimePolicy.transition("DRAFT", "SUBMIT", 60, false));
    assertEquals("RETURNED", TimePolicy.transition("SUBMITTED", "RETURN", 60, true));
    assertEquals("DRAFT", TimePolicy.transition("SUBMITTED", "RECALL", 60, false));
    assertThrows(
        CommandConflict.class, () -> TimePolicy.transition("RETURNED", "SUBMIT", 60, false));
    assertEquals("SUBMITTED", TimePolicy.transition("CORRECTED", "SUBMIT", 60, false));
    assertEquals("APPROVED", TimePolicy.transition("SUBMITTED", "APPROVE", 60, true));
    assertThrows(
        AccessDenied.class, () -> TimePolicy.transition("SUBMITTED", "APPROVE", 60, false));
    assertThrows(CommandConflict.class, () -> TimePolicy.editable("APPROVED"));
  }
}
