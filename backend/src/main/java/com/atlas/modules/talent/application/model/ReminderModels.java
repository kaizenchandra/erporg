package com.atlas.modules.talent.application.model;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class ReminderModels {
  private ReminderModels() {}

  public record Configure(
      BigDecimal version,
      boolean enabled,
      int thresholdMinutes,
      int frequencyMinutes,
      String reason,
      String idempotencyKey) {}

  public record Command(String reason, String idempotencyKey) {}

  public record Policy(
      String id,
      long version,
      boolean enabled,
      int thresholdMinutes,
      int frequencyMinutes,
      String owner,
      String ownerEmployee,
      String reason,
      Instant recordedAt,
      String correlationId) {}

  public record State(
      String policyId, long version, String status, int failures, Instant nextDue) {}

  public record Source(String eventId, String kind, String recordId, long version) {}

  public record Run(
      String id,
      String policyId,
      String actor,
      boolean scheduled,
      String status,
      int processed,
      int delivered,
      int skipped,
      String reason,
      Instant recordedAt,
      String correlationId) {}

  public record Item(
      String id,
      String runId,
      String eventId,
      String kind,
      String recordId,
      LocalDate day,
      String status,
      String reason,
      int recipients) {}

  public record Context(Policy policy, State state, List<Run> runs, boolean schedulerEnabled) {}

  public record Due(String tenant, Policy policy) {}
}
