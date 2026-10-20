package com.atlas.modules.finance.adapter.in.integration;

import com.atlas.modules.finance.api.PayrollJournalAccess;
import com.atlas.modules.finance.application.model.FinanceModels.PayrollCommand;
import com.atlas.modules.finance.application.port.in.FinanceUseCases;

public final class PayrollJournalAdapter implements PayrollJournalAccess {
  private final FinanceUseCases finance;

  public PayrollJournalAdapter(FinanceUseCases finance) {
    this.finance = finance;
  }

  public String prepare(String subject, Request c, String correlation) {
    return finance
        .preparePayroll(
            subject,
            new PayrollCommand(
                c.runId(),
                c.entity(),
                c.currency(),
                c.postingDate(),
                c.costCentre(),
                c.gross(),
                c.deduction(),
                c.net(),
                c.reason(),
                c.idempotencyKey()),
            correlation)
        .id();
  }
}
