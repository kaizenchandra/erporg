package com.atlas.modules.payroll.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface CompensationAccess {
  record Salary(
      String id,
      String employeeId,
      String currency,
      BigDecimal annualSalary,
      BigDecimal annualHours,
      LocalDate effectiveDate,
      LocalDate episodeStart,
      String sourceId,
      long revision) {}

  Salary salary(String subject, String employee, LocalDate date);
}
