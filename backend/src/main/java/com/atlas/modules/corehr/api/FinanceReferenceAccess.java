package com.atlas.modules.corehr.api;

import java.time.LocalDate;

public interface FinanceReferenceAccess {
  record Entity(String code, String currency, LocalDate effectiveFrom, LocalDate retiredOn) {}

  record Worker(String id, String status, LocalDate start, String entityCode) {}

  Entity entity(String subject, String code);

  Worker worker(String subject, String id);
}
