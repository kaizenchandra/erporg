package com.atlas.modules.benefits.application.port.out;

import com.atlas.modules.benefits.application.model.BenefitContext;

public interface WorkforcePort {
  BenefitContext self(String subject);

  BenefitContext employee(String subject, String employeeId);
}
