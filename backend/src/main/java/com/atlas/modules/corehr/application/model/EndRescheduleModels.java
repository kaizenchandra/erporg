package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class EndRescheduleModels {
  private EndRescheduleModels() {}

  public record Submit(
      LocalDate lastWorkingDate, String reason, BigDecimal version, String idempotencyKey) {}

  public record Review(String decision, String reason, BigDecimal version, String idempotencyKey) {}

  public record Context(
      EmploymentEnd currentSchedule,
      long employeeVersion,
      boolean withdrawalPending,
      List<EndReschedule> history) {}
}
