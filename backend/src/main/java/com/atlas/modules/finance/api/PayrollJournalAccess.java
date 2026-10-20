package com.atlas.modules.finance.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface PayrollJournalAccess {
  record Request(
      String runId,
      String entity,
      String currency,
      LocalDate postingDate,
      String costCentre,
      BigDecimal gross,
      BigDecimal deduction,
      BigDecimal net,
      String reason,
      String idempotencyKey) {}

  String prepare(String subject, Request request, String correlation);
}
