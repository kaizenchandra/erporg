package com.atlas.modules.corehr.api;

import java.time.LocalDate;
import java.util.List;

/** Published, authorised projection and existing hire/onboarding commands. */
public interface TalentWorkforceAccess {
  record Identity(String subject, String tenant, String employeeId, String role) {}

  record Worker(
      String id,
      String name,
      String title,
      String department,
      String managerId,
      String status,
      LocalDate start,
      long version) {}

  record Workers(List<Worker> items, long total) {}

  record HireInput(
      String name,
      String email,
      String phone,
      String title,
      String department,
      String location,
      String managerId,
      LocalDate start) {}

  record Hire(String id, String status, String employeeId) {}

  List<String> hrReviewers(String subject);

  Identity self(String subject);

  boolean hasRole(String subject, String employee, String role);

  Worker worker(String subject, String id);

  Workers workers(String subject, String search, int page);

  Hire submitHire(String subject, HireInput input, String reason, String key, String correlation);

  Hire decideHire(String subject, String id, String decision, String reason, String correlation);

  void completeOnboarding(
      String subject,
      String hireId,
      long employeeVersion,
      String reason,
      String key,
      String correlation);
}
