package com.atlas.modules.finance.adapter.in.integration;

import com.atlas.modules.finance.api.PayrollFinanceAccess;
import com.atlas.modules.finance.application.port.in.FinanceUseCases;
import java.util.List;

public final class PayrollFinanceAdapter implements PayrollFinanceAccess {
  private final FinanceUseCases finance;

  public PayrollFinanceAdapter(FinanceUseCases finance) {
    this.finance = finance;
  }

  public List<Grant> grants(String subject) {
    return finance.context(subject).grants().stream()
        .map(g -> new Grant(g.entity(), g.permission()))
        .toList();
  }

  public Ledger ledger(String subject, String entity) {
    var l = finance.payrollLedger(subject, entity);
    return new Ledger(l.entity(), l.currency(), l.effectiveFrom(), l.costCentres());
  }
}
