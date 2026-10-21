package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.InitialIdentityModels.*;

public interface InitialIdentityUseCases {
  Context context(String actor, String employee);

  Binding submit(String actor, String employee, Submit command, String correlation);

  Binding review(String actor, String id, Review command, String correlation);

  void verifyIssuer(String subject, String issuer);
}
