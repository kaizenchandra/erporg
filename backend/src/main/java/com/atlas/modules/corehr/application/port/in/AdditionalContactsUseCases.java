package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.ContactModels.*;
import com.atlas.modules.corehr.domain.model.ContactRequest;

public interface AdditionalContactsUseCases {
  Page inbox(String subject, String scope, Query query);

  Context context(String subject, String employeeId, Query query);

  ContactRequest submit(String subject, String employeeId, Submit command, String correlation);

  ContactRequest review(
      String subject, String employeeId, String requestId, Review command, String correlation);
}
