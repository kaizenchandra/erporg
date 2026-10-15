package com.atlas.modules.documents.adapter.out.integration;

import com.atlas.modules.corehr.api.WorkforceAccess;
import com.atlas.modules.documents.application.model.DocumentActor;
import com.atlas.modules.documents.application.port.out.DocumentIdentity;

public final class CoreHrDocumentIdentity implements DocumentIdentity {
  private final WorkforceAccess workforce;

  public CoreHrDocumentIdentity(WorkforceAccess workforce) {
    this.workforce = workforce;
  }

  public DocumentActor actor(String subject) {
    var c = workforce.self(subject);
    return new DocumentActor(c.subject(), c.tenant(), c.employeeId(), c.role());
  }

  public String employee(String subject, String employeeId) {
    return workforce.employee(subject, employeeId).employeeId();
  }
}
