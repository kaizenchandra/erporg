package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.RehireAccessModels.*;
import com.atlas.modules.corehr.domain.model.RehireAccessRevocation;

public interface RehireAccessUseCases {
  Context context(String subject, String employeeId);

  RehireAccessRevocation revoke(
      String subject, String employeeId, Revoke command, String correlationId);
}
