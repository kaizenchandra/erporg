package com.atlas.modules.timekeeping.adapter.out.integration;

import com.atlas.modules.corehr.api.TimeWorkforceAccess;
import com.atlas.modules.timekeeping.application.model.TimeModels.*;
import com.atlas.modules.timekeeping.application.port.out.TimeWorkforce;

public final class CoreHrTimeWorkforce implements TimeWorkforce {
  private final TimeWorkforceAccess access;

  public CoreHrTimeWorkforce(TimeWorkforceAccess access) {
    this.access = access;
  }

  private Actor map(TimeWorkforceAccess.Context c) {
    return new Actor(c.subject(), c.tenant(), c.employeeId(), c.role(), c.status(), c.start());
  }

  public Actor self(String subject) {
    return map(access.self(subject));
  }

  public Actor employee(String subject, String id) {
    return map(access.employee(subject, id));
  }

  public Scope scope(String subject) {
    var s = access.scope(subject);
    return new Scope(map(s.actor()), s.employeeIds(), s.all());
  }
}
