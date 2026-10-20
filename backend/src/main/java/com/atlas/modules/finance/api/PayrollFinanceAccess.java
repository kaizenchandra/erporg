package com.atlas.modules.finance.api;

import java.time.LocalDate;
import java.util.List;

public interface PayrollFinanceAccess {
  record Grant(String entity, String permission) {}

  record Ledger(
      String entity, String currency, LocalDate effectiveFrom, List<String> costCentres) {}

  List<Grant> grants(String subject);

  Ledger ledger(String subject, String entity);
}
