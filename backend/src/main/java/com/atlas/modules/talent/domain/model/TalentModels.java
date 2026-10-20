package com.atlas.modules.talent.domain.model;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class TalentModels {
  private TalentModels() {}

  public record Stamp(
      String id,
      long version,
      String status,
      String preparedBy,
      String preparedEmployee,
      Instant recordedAt) {
    public Stamp next(String state, Instant at) {
      return new Stamp(id, version + 1, state, preparedBy, preparedEmployee, at);
    }
  }

  public record Candidate(
      String id,
      String name,
      String email,
      String phone,
      String stage,
      String interviewAt,
      String interviewNotes,
      String offer,
      String hireId,
      String employeeId) {}

  public record Requisition(
      Stamp meta,
      String title,
      String department,
      String location,
      String managerId,
      LocalDate start,
      List<Candidate> candidates) {}

  public record Task(
      String id,
      String title,
      String assignee,
      List<String> dependsOn,
      boolean complete,
      String completedBy,
      Instant completedAt,
      String evidence) {}

  public record Onboarding(
      Stamp meta, String hireId, String employeeId, String joiningForm, List<Task> tasks) {}

  public record Goal(String id, String text, String feedback) {}

  public record Review(
      Stamp meta,
      String employeeId,
      String cycle,
      LocalDate from,
      LocalDate through,
      int minimumRating,
      int maximumRating,
      List<Goal> goals,
      String selfReview,
      Integer rating,
      String decisionComment) {}

  public record Course(Stamp meta, String title, String description, String role, int validDays) {}

  public record Enrollment(
      Stamp meta,
      String courseId,
      String employeeId,
      String courseTitle,
      int validDays,
      LocalDate due,
      LocalDate completed,
      LocalDate expires,
      String evidence) {}

  public record Proposal(
      String employeeId,
      BigDecimal currentSalary,
      BigDecimal proposedSalary,
      BigDecimal bonus,
      LocalDate effectiveDate) {}

  public record Compensation(
      Stamp meta,
      String title,
      String currency,
      BigDecimal budget,
      List<Proposal> proposals,
      BigDecimal total,
      String payrollIntakeId) {}

  public record Nominee(String employeeId, String skills, String readiness, String assessment) {}

  public record Succession(Stamp meta, String position, List<Nominee> nominees) {}

  public record CostLine(
      String dimension, int baselineHeadcount, int proposedHeadcount, BigDecimal annualUnitCost) {}

  public record Scenario(
      Stamp meta,
      String title,
      String currency,
      LocalDate asOf,
      List<String> workerSnapshot,
      List<CostLine> lines,
      int baselineHeadcount,
      int proposedHeadcount,
      BigDecimal baselineCost,
      BigDecimal proposedCost,
      BigDecimal variance) {}
}
