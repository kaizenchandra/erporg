package com.atlas.modules.talent.application.model;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class TalentCommands {
  public record Delegate(
      String kind,
      String recordId,
      java.math.BigDecimal version,
      String delegateEmployee,
      java.time.LocalDate from,
      java.time.LocalDate through,
      String reason,
      String idempotencyKey) {}

  public record RevokeDelegation(String reason, String idempotencyKey) {}

  private TalentCommands() {}

  public record RequisitionInput(
      String title,
      String department,
      String location,
      String managerId,
      LocalDate start,
      String reason,
      String idempotencyKey) {}

  public record Decision(BigDecimal version, String action, String reason, String idempotencyKey) {}

  public record CandidateInput(
      BigDecimal version,
      String candidateId,
      String action,
      String name,
      String email,
      String phone,
      String interviewAt,
      String notes,
      String offer,
      String reason,
      String idempotencyKey) {}

  public record TaskInput(String title, String assignee, List<String> dependsOn) {}

  public record Joining(
      BigDecimal version,
      String action,
      String taskId,
      String joiningForm,
      List<TaskInput> tasks,
      String evidence,
      BigDecimal employeeVersion,
      String reason,
      String idempotencyKey) {}

  public record ReviewInput(
      String employeeId,
      String cycle,
      LocalDate from,
      LocalDate through,
      Integer minimumRating,
      Integer maximumRating,
      String reason,
      String idempotencyKey) {}

  public record ReviewAction(
      BigDecimal version,
      String action,
      String goalId,
      String goal,
      String feedback,
      String selfReview,
      Integer rating,
      String reason,
      String idempotencyKey) {}

  public record CourseInput(
      String title,
      String description,
      String role,
      Integer validDays,
      String reason,
      String idempotencyKey) {}

  public record Assignment(
      String courseId, String employeeId, LocalDate due, String reason, String idempotencyKey) {}

  public record LearningAction(
      BigDecimal version, String action, String evidence, String reason, String idempotencyKey) {}

  public record ProposalInput(
      String employeeId,
      String currentSalary,
      String proposedSalary,
      String bonus,
      LocalDate effectiveDate) {}

  public record CompensationInput(
      String title,
      String currency,
      String budget,
      List<ProposalInput> proposals,
      String reason,
      String idempotencyKey) {}

  public record NomineeInput(
      String employeeId, String skills, String readiness, String assessment) {}

  public record SuccessionInput(
      String position, List<NomineeInput> nominees, String reason, String idempotencyKey) {}

  public record SuccessionUpdate(
      BigDecimal version,
      String employeeId,
      String skills,
      String readiness,
      String assessment,
      String reason,
      String idempotencyKey) {}

  public record CostInput(
      String dimension,
      Integer baselineHeadcount,
      Integer proposedHeadcount,
      String annualUnitCost) {}

  public record ScenarioInput(
      String title,
      String currency,
      LocalDate asOf,
      List<String> workerIds,
      List<CostInput> lines,
      String reason,
      String idempotencyKey) {}

  public record Actor(String subject, String tenant, String employeeId, String role) {}

  public record Worker(
      String id,
      String name,
      String title,
      String department,
      String managerId,
      String status,
      LocalDate start,
      long version) {}

  public record Workers(List<Worker> items, long total) {}

  public record Context(Actor actor, Workers workers) {}

  public record Page<T>(List<T> items, long total, int page, int size) {}

  public record Audit(
      String id,
      String kind,
      String recordId,
      String actor,
      String employeeId,
      String action,
      String reason,
      String before,
      String after,
      Instant recordedAt,
      String correlationId) {}

  public record Receipt(String fingerprint, String result) {}
}
