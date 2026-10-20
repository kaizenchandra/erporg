package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.ReferenceHistoryModels.*;

public interface ReferenceHistoryStore {
  Page history(String tenant, Query query);
}
