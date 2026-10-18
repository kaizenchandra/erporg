package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.IdentityReplacementModels.*;
import com.atlas.modules.corehr.domain.model.IdentityReplacement;

public interface IdentityReplacementUseCases {
  Context context(String subject, String employee);

  IdentityReplacement submit(String subject, String employee, Submit command, String correlation);

  IdentityReplacement review(
      String subject, String employee, String id, Review command, String correlation);
}
