package com.atlas.modules.benefits.application.port.out;

import com.atlas.modules.benefits.application.model.PlanHistoryModels.*;

public interface PlanHistoryStore {
  Page history(String tenant, Query query);
}
