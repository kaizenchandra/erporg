package com.atlas.modules.payroll.adapter.out.integration;

import com.atlas.modules.corehr.api.TalentWorkforceAccess;
import com.atlas.modules.finance.api.PayrollFinanceAccess;
import com.atlas.modules.payroll.application.port.out.CompensationPorts.*;

public final class CompensationEnvironment implements Environment {
  private final TalentWorkforceAccess workforce;
  private final PayrollFinanceAccess finance;

  public CompensationEnvironment(TalentWorkforceAccess w, PayrollFinanceAccess f) {
    workforce = w;
    finance = f;
  }

  public Identity self(String s) {
    var a = workforce.self(s);
    return new Identity(a.subject(), a.tenant(), a.employeeId(), a.role());
  }

  public Worker worker(String s, String id) {
    var w = workforce.worker(s, id);
    return new Worker(w.id(), w.status(), w.start());
  }

  public boolean payroll(String s, String e) {
    return finance.grants(s).stream()
        .anyMatch(
            g ->
                g.entity().equals(e)
                    && java.util.Set.of("PAYROLL_PREPARE", "PAYROLL_APPROVE")
                        .contains(g.permission()));
  }
}
