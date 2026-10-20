package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class CalendarAmendmentModels {
  private CalendarAmendmentModels() {}

  public record Amend(
      BigDecimal version,
      String effectiveDate,
      List<BigDecimal> workingDays,
      List<String> closedDates,
      String reason,
      String idempotencyKey) {}

  public record RevisionEntry(
      CalendarRevision revision,
      String createdBy,
      String reason,
      Instant recordedAt,
      String correlationId) {}

  public record Draft(
      LocalDate effectiveDate, List<Integer> workingDays, List<LocalDate> closedDates) {}

  public record Context(
      CalendarModels.Entry base,
      WorkCalendar currentPolicy,
      int currentVersion,
      LocalDate currentEffectiveDate,
      int version,
      List<RevisionEntry> history,
      Draft draft,
      CalendarRetirementModels.Entry retirement,
      String retirementStatus,
      LocalDate retirementDraft,
      boolean assignmentRecorded) {}
}
