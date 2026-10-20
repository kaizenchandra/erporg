package com.atlas.modules.corehr.domain.model;

import java.time.*;
import java.util.*;

public final class LegalEntityRetirement {
  private LegalEntityRetirement() {}

  public record Proposal(
      String id,
      String code,
      int ordinal,
      long sourceVersion,
      LocalDate effectiveDate,
      String reason,
      String requestedBy,
      String requesterEmployeeId,
      Instant recordedAt,
      String correlationId) {}

  public record Decision(
      String requestId,
      String decision,
      String comment,
      String actor,
      String actorEmployeeId,
      Instant recordedAt,
      String correlationId) {}

  public static String text(String value, String field) {
    if (value == null
        || value.isBlank()
        || value.length() > 500
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new ChangeRequest.Validation(
          Map.of(field, "Enter 1–500 characters without control characters."));
    return value.trim();
  }

  public static void dates(LocalDate date, LocalDate from, LocalDate today) {
    if (date == null
        || date.isBefore(from)
        || date.isBefore(today)
        || date.isAfter(today.plusYears(1)))
      throw new ChangeRequest.Validation(
          Map.of("effectiveDate", "Choose today–one year ahead, on or after the reference start."));
  }

  public static void independent(Proposal p, String subject, String employeeId) {
    if (p.requestedBy().equals(subject) || p.requesterEmployeeId().equals(employeeId))
      throw new ChangeRequest.Denied();
  }

  public static void decision(String decision, String comment, LocalDate date, LocalDate today) {
    if (!Set.of("APPROVED", "REJECTED").contains(decision == null ? "" : decision))
      throw new ChangeRequest.Validation(Map.of("decision", "Choose approval or rejection."));
    text(comment, "comment");
    if (decision.equals("APPROVED") && date.isBefore(today))
      throw new ChangeRequest.Conflict(
          "The proposed date has elapsed. Reject it and submit a fresh proposal.");
  }
}
