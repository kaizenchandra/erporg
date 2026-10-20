package com.atlas.modules.payroll.adapter.in.integration;

import com.atlas.modules.payroll.api.TalentIntakeAccess;
import com.atlas.modules.payroll.application.port.in.TalentIntakeUseCases;
import java.util.List;

public final class TalentIntakeAdapter implements TalentIntakeAccess {
  private final TalentIntakeUseCases p;

  public TalentIntakeAdapter(TalentIntakeUseCases p) {
    this.p = p;
  }

  public String receive(String s, Request r, String reason, String corr) {
    return p.receive(s, r, reason, corr);
  }

  public List<Intake> pending(String s, int page) {
    return p.pending(s, page);
  }
}
