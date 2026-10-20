package com.atlas.modules.payroll.adapter.out.integration;

import com.atlas.modules.corehr.api.*;
import com.atlas.modules.payroll.application.port.out.TalentIntakePorts.*;

public final class TalentIdentityAdapter implements IdentityAccess {
  private final TalentWorkforceAccess w;

  public TalentIdentityAdapter(TalentWorkforceAccess w) {
    this.w = w;
  }

  public Identity self(String s) {
    var a = w.self(s);
    return new Identity(a.subject(), a.tenant(), a.employeeId(), a.role());
  }

  public void worker(String s, String employee) {
    w.worker(s, employee);
  }
}
