package com.atlas.modules.documents.application.port.out;

import com.atlas.modules.documents.application.model.DocumentActor;

public interface DocumentIdentity {
  DocumentActor actor(String subject);

  default String employee(String subject, String employeeId) {
    if (!actor(subject).employeeId().equals(employeeId))
      throw new com.atlas.shared.domain.AccessDenied();
    return employeeId;
  }
}
