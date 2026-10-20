package com.atlas.modules.finance.application.port.in;

import com.atlas.modules.finance.application.model.FinanceModels.*;
import java.time.LocalDate;

public interface FinanceUseCases {
  Journal preparePayroll(String subject, PayrollCommand command, String correlation);

  Ledger payrollLedger(String subject, String entity);

  Context context(String subject);

  Ledger setup(String subject, Setup c, String correlation);

  Page<Journal> journals(String subject, String entity, String status, int page);

  Journal journal(String subject, String id);

  Journal save(String subject, SaveJournal c, String correlation);

  Journal action(String subject, String id, Action c, String correlation);

  Period period(String subject, String entity, LocalDate month);

  Period setPeriod(String subject, SetPeriod c, String correlation);

  TrialBalance trialBalance(String subject, String entity, LocalDate from, LocalDate through);

  Page<Audit> history(String subject, String entity, int page);
}
