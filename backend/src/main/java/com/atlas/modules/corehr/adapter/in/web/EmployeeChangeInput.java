package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.Commands.Submit;
import java.math.BigDecimal;
import java.time.LocalDate;

public record EmployeeChangeInput(
    String employeeId,
    String field,
    String newValue,
    LocalDate effectiveDate,
    String reason,
    BigDecimal version,
    String idempotencyKey) {
  Submit command() {
    return new Submit(
        employeeId,
        field,
        newValue,
        effectiveDate,
        reason,
        CoreHrCommandVersion.exact(version),
        idempotencyKey);
  }
}
