package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.*;
import java.util.*;

public final class EmployeeCalendarModels {
  private EmployeeCalendarModels() {}

  public record Preview(String employeeId, String calendarName, CalendarModels.Preview preview) {}

  public record HistoryQuery(int page, int size) {
    public HistoryQuery {
      if (page < 0 || page > 100000 || size < 1 || size > 50)
        throw new IllegalArgumentException("Use page 0–100000 and size 1–50.");
    }
  }

  /** Public reference provenance only; no requester, reason or configuration audit data. */
  public record Assignment(
      String versionId,
      java.time.LocalDate episodeStart,
      java.time.LocalDate effectiveDate,
      java.time.Instant recordedAt,
      String calendarCode,
      String calendarName,
      String timeZone) {}

  public record History(
      String employeeId,
      java.time.LocalDate asOf,
      java.time.LocalDate currentEpisodeStart,
      List<Assignment> items,
      long total,
      int page,
      int size) {}

  public record Review(
      String requestId,
      String code,
      Integer expectedPolicyVersion,
      int currentPolicyVersion,
      boolean policyChanged,
      boolean retirementRecorded,
      WorkCalendar base,
      List<CalendarRevision> history) {}
}
