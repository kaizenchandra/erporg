package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.RehireActivationModels.*;
import com.atlas.modules.corehr.domain.model.RehireActivation;

public interface RehireActivationUseCases {
  Context context(String subject, String employeeId);

  RehireActivation submit(String subject, String employeeId, Submit command, String correlation);

  RehireActivation review(
      String subject, String employeeId, String activationId, Review command, String correlation);
}
