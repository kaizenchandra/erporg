package com.atlas.modules.payroll.api;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Durable payroll receipt. RECEIVED is not calculated, paid or posted. */
public interface TalentIntakeAccess {
  record Award(
      String employeeId,
      BigDecimal currentSalary,
      BigDecimal proposedSalary,
      BigDecimal bonus,
      LocalDate effectiveDate) {}

  record Request(
      String sourceId,
      String currency,
      String preparedBy,
      String preparedEmployee,
      List<Award> awards) {}

  record Intake(
      String id,
      String sourceId,
      String currency,
      List<Award> awards,
      String status,
      String receivedBy,
      Instant recordedAt) {}

  String receive(String subject, Request request, String reason, String correlation);

  List<Intake> pending(String subject, int page);
}
