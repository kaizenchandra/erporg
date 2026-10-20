package com.atlas.modules.timekeeping.domain.model;

import com.atlas.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class TimePolicy {
  private TimePolicy() {}

  public static void require(boolean condition, String field, String message) {
    if (!condition) throw new InputValidation(Map.of(field, message));
  }

  public static long revision(BigDecimal n) {
    try {
      if (n == null || n.signum() < 0) throw new ArithmeticException();
      return n.longValueExact();
    } catch (ArithmeticException e) {
      throw new InputValidation(Map.of("version", "An exact nonnegative version is required."));
    }
  }

  public static String text(String value, String field, int max) {
    require(
        value != null
            && !value.isBlank()
            && value.length() <= max
            && !value.codePoints().anyMatch(Character::isISOControl),
        field,
        "Enter1–" + max + " characters without control characters.");
    return value.trim();
  }

  public static ZoneId zone(String value) {
    try {
      require(
          value != null && ZoneId.getAvailableZoneIds().contains(value),
          "zone",
          "Choose an IANA time zone.");
      return ZoneId.of(value);
    } catch (DateTimeException e) {
      throw new InputValidation(Map.of("zone", "Choose an IANA time zone."));
    }
  }

  public static Instant timestamp(String value, ZoneId zone, String field) {
    try {
      var dt = OffsetDateTime.parse(value);
      require(
          zone.getRules().getValidOffsets(dt.toLocalDateTime()).contains(dt.getOffset()),
          field,
          "The timestamp offset must exist in the selected zone; check daylight-saving boundaries.");
      require(
          dt.getNano() % 1000 == 0,
          field,
          "Use at most six fractional second digits; no implicit timestamp rounding.");
      return dt.toInstant();
    } catch (DateTimeException | NullPointerException e) {
      throw new InputValidation(
          Map.of(
              field,
              "Use an ISO timestamp with its explicit offset, for example2026-10-20T09:00:00+01:00."));
    }
  }

  public static void interval(
      Instant start, Instant end, Instant now, LocalDate employmentStart, ZoneId zone) {
    require(start != null && !start.isAfter(now), "start", "Start must not be in the future.");
    require(
        !start.atZone(zone).toLocalDate().isBefore(employmentStart),
        "start",
        "Start must be within this employment episode.");
    if (end != null) {
      require(
          end.isAfter(start) && !end.isAfter(now),
          "end",
          "End must follow start and must not be in the future.");
      require(
          Duration.between(start, end).compareTo(Duration.ofHours(24)) <= 0,
          "end",
          "A shift cannot exceed24 elapsed hours; correct the interval.");
    }
  }

  public static int wholeMinutes(BigDecimal value) {
    try {
      int n = value.intValueExact();
      require(n >= 0 && n <= 1440, "minutes", "Use0–1440 whole minutes per day.");
      return n;
    } catch (ArithmeticException | NullPointerException e) {
      throw new InputValidation(Map.of("minutes", "Use0–1440 whole minutes per day."));
    }
  }

  public static int minutes(BigDecimal hours) {
    try {
      require(
          hours != null, "hours", "Enter hours for every date, using0 when no work was performed.");
      int n = hours.multiply(BigDecimal.valueOf(60)).intValueExact();
      require(n >= 0 && n <= 1440, "hours", "Use0–24 hours per day in exact whole minutes.");
      return n;
    } catch (ArithmeticException e) {
      throw new InputValidation(
          Map.of("hours", "Hours must convert exactly to whole minutes, up to24 per day."));
    }
  }

  public static void week(LocalDate start) {
    require(
        start != null && start.getDayOfWeek() == DayOfWeek.MONDAY,
        "week",
        "Choose the Monday starting the week.");
  }

  public static void editable(String status) {
    if (!Set.of("DRAFT", "REJECTED", "RETURNED", "CORRECTED").contains(status))
      throw new CommandConflict(
          "Only draft, returned, rejected or corrected timesheets can be edited.");
  }

  public static String transition(String status, String action, int total, boolean independent) {
    return switch (action) {
      case "SUBMIT" -> {
        editable(status);
        if (status.equals("RETURNED"))
          throw new CommandConflict(
              "Save corrected hours and a reason before resubmitting returned time.");
        require(total > 0, "entries", "Enter working hours before submitting.");
        yield "SUBMITTED";
      }
      case "RECALL" -> {
        if (!status.equals("SUBMITTED"))
          throw new CommandConflict("Only pending submissions can be recalled.");
        yield "DRAFT";
      }
      case "APPROVE", "REJECT", "RETURN" -> {
        if (!independent) throw new AccessDenied();
        if (!status.equals("SUBMITTED"))
          throw new CommandConflict("Only submitted timesheets can be reviewed.");
        yield switch (action) {
          case "APPROVE" -> "APPROVED";
          case "RETURN" -> "RETURNED";
          default -> "REJECTED";
        };
      }
      default ->
          throw new InputValidation(
              Map.of("action", "Choose SUBMIT, RECALL, APPROVE, RETURN or REJECT."));
    };
  }

  public static void approvedSource(String status) {
    if (!"APPROVED".equals(status))
      throw new com.atlas.shared.domain.CommandConflict("Only approved time can enter payroll.");
  }
}
