package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.EmployeeCalendarModels.*;
import com.atlas.modules.corehr.domain.model.Actor;

public interface EmployeeCalendarUseCases {
  History history(Actor actor, String employeeId, HistoryQuery query);

  Preview preview(Actor actor, String employeeId, String date);

  Review review(Actor actor, String requestId);
}
