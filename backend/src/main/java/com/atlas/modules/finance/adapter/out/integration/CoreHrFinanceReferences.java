package com.atlas.modules.finance.adapter.out.integration;

import com.atlas.modules.corehr.api.*;
import com.atlas.modules.finance.application.model.FinanceModels.*;
import com.atlas.modules.finance.application.port.out.FinancePorts.References;

public final class CoreHrFinanceReferences implements References {
  private final WorkforceAccess workforce;
  private final FinanceReferenceAccess refs;

  public CoreHrFinanceReferences(WorkforceAccess workforce, FinanceReferenceAccess refs) {
    this.workforce = workforce;
    this.refs = refs;
  }

  public Identity self(String subject) {
    workforce.requireActive(subject);
    var a = workforce.self(subject);
    return new Identity(a.subject(), a.tenant(), a.employeeId());
  }

  public Reference entity(String subject, String code) {
    var e = refs.entity(subject, code);
    return new Reference(e.code(), e.currency(), e.effectiveFrom(), e.retiredOn());
  }
}
