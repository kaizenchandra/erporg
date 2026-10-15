package com.atlas.modules.benefits.application.port.in;

import com.atlas.modules.benefits.application.model.*;
import java.util.List;

public interface BenefitsUseCases {
  PlanPage plans(String subject, PlanQuery query);

  PlanView create(String subject, CreatePlan command, String correlation);

  List<PlanAudit> history(String subject);
}
