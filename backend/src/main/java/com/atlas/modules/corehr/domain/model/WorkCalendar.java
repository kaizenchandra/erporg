package com.atlas.modules.corehr.domain.model;

import java.time.*;
import java.util.*;

/** Declared company policy; no public-holiday, entitlement or payroll inference. */
public record WorkCalendar(
    String code,
    String name,
    String timeZone,
    LocalDate effectiveFrom,
    LocalDate effectiveUntil,
    List<Integer> workingDays,
    List<LocalDate> closedDates) {
  public WorkCalendar {
    var errors = new LinkedHashMap<String, String>();
    code = text(code, "code", 30, errors);
    name = text(name, "name", 120, errors);
    timeZone = text(timeZone, "timeZone", 80, errors);
    if (!code.matches("[A-Z0-9-]{2,30}"))
      errors.put("code", "Use 2–30 uppercase letters, digits or hyphens.");
    if (!timeZone.matches("[A-Za-z0-9_+/-]{1,80}"))
      errors.put("timeZone", "Choose a region time-zone ID.");
    if (effectiveFrom == null || effectiveFrom.getYear() < 1 || effectiveFrom.getYear() > 9999)
      errors.put("effectiveFrom", "Enter a valid start date.");
    if (effectiveUntil == null || effectiveUntil.getYear() < 1 || effectiveUntil.getYear() > 9999)
      errors.put("effectiveUntil", "Enter a valid end date.");
    if (effectiveFrom != null
        && effectiveUntil != null
        && (effectiveUntil.isBefore(effectiveFrom)
            || !effectiveUntil.isBefore(effectiveFrom.plusYears(1))))
      errors.put("effectiveUntil", "End must be on/after start and before its first anniversary.");
    if (workingDays == null
        || workingDays.isEmpty()
        || workingDays.size() > 7
        || workingDays.stream().anyMatch(d -> d == null || d < 1 || d > 7)
        || new HashSet<>(workingDays).size() != workingDays.size())
      errors.put("workingDays", "Choose unique working weekdays, Monday=1 to Sunday=7.");
    if (closedDates == null
        || closedDates.size() > 50
        || closedDates.stream().anyMatch(Objects::isNull)
        || new HashSet<>(closedDates).size() != closedDates.size())
      errors.put("closedDates", "Enter at most 50 unique company-closure dates.");
    else if (effectiveFrom != null
        && effectiveUntil != null
        && closedDates.stream()
            .anyMatch(d -> d.isBefore(effectiveFrom) || d.isAfter(effectiveUntil)))
      errors.put("closedDates", "Closures must lie within the calendar's validity period.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
    workingDays = workingDays.stream().sorted().toList();
    closedDates = closedDates.stream().sorted().toList();
  }

  private static String text(String v, String field, int max, Map<String, String> errors) {
    String s = v == null ? "" : v.trim();
    if (s.isBlank()
        || s.length() > max
        || (v != null && v.codePoints().anyMatch(Character::isISOControl)))
      errors.put(field, "Enter 1–" + max + " characters without control characters.");
    return s;
  }

  public static LocalDate date(String value, String field) {
    try {
      if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000"))
        throw new IllegalArgumentException();
      return LocalDate.parse(value);
    } catch (RuntimeException ex) {
      throw new ChangeRequest.Validation(
          Map.of(field, "Use a valid ISO date (YYYY-MM-DD), year 0001–9999."));
    }
  }

  public void validateNew(LocalDate today) {
    var errors = new LinkedHashMap<String, String>();
    if (!ZoneId.getAvailableZoneIds().contains(timeZone))
      errors.put("timeZone", "Choose an available region time-zone ID.");
    if (effectiveFrom.isBefore(today) || effectiveFrom.isAfter(today.plusYears(1)))
      errors.put("effectiveFrom", "Start must be today or within the next year.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
  }

  public String classification(LocalDate date) {
    if (date == null || date.isBefore(effectiveFrom) || date.isAfter(effectiveUntil))
      throw new ChangeRequest.Validation(
          Map.of("date", "Choose a date within the calendar's validity period."));
    return closedDates.contains(date)
        ? "COMPANY_CLOSURE"
        : workingDays.contains(date.getDayOfWeek().getValue())
            ? "WORKING_DAY"
            : "NON_WORKING_WEEKDAY";
  }
}
