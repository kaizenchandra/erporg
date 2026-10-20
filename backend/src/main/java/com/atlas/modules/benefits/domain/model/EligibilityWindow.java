package com.atlas.modules.benefits.domain.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** Demonstration eligibility selection criteria; adapters translate, never derive, this policy. */
public record EligibilityWindow(
    boolean employmentAllowed, LocalDate evaluatedOn, long completedServiceDays) {
  public static EligibilityWindow forEmployment(String status, LocalDate start, LocalDate today) {
    return new EligibilityWindow(
        Set.of("Active", "On leave").contains(status),
        today,
        ChronoUnit.DAYS.between(start, today));
  }

  public boolean planStarted(LocalDate start) {
    return !start.isAfter(evaluatedOn);
  }

  public boolean serviceReached(int requiredDays) {
    return requiredDays <= completedServiceDays;
  }
}
