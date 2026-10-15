package com.atlas.modules.benefits.adapter.out.integration;

import com.atlas.modules.benefits.application.model.BenefitContext;
import com.atlas.modules.benefits.application.port.out.WorkforcePort;
import com.atlas.modules.corehr.api.WorkforceAccess;

public final class CoreHrWorkforceAdapter implements WorkforcePort {
  private final WorkforceAccess access;

  public CoreHrWorkforceAdapter(WorkforceAccess access) {
    this.access = access;
  }

  public BenefitContext self(String subject) {
    return map(access.self(subject));
  }

  public BenefitContext employee(String subject, String employeeId) {
    return map(access.employee(subject, employeeId));
  }

  private BenefitContext map(WorkforceAccess.Context c) {
    return new BenefitContext(
        c.subject(),
        c.tenant(),
        c.employeeId(),
        c.role(),
        c.employmentStatus(),
        c.employmentStart());
  }
}
