package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.EmployeeHistoryModels.*;
import com.atlas.modules.corehr.domain.model.Actor;

public interface EmployeeHistoryUseCases {
  Page history(Actor actor, String employeeId, Query query);
}
