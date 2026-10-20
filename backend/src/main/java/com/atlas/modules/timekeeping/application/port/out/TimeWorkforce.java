package com.atlas.modules.timekeeping.application.port.out;

import com.atlas.modules.timekeeping.application.model.TimeModels.*;

public interface TimeWorkforce {
  Actor self(String subject);

  Actor employee(String subject, String employeeId);

  Scope scope(String subject);
}
