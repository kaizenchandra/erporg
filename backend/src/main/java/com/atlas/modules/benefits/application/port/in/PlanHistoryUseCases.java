package com.atlas.modules.benefits.application.port.in;

import com.atlas.modules.benefits.application.model.PlanHistoryModels.*;

public interface PlanHistoryUseCases {
  Page history(String subject, Query query);
}
