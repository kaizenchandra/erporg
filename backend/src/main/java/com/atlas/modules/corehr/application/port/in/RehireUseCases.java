package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.RehireModels.*;
import com.atlas.modules.corehr.domain.model.Rehire;

public interface RehireUseCases {
  Context context(String subject, String employeeId);

  Rehire submit(String subject, String employeeId, Submit command, String correlation);

  Rehire review(
      String subject, String employeeId, String requestId, Review command, String correlation);
}
