package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.ReferenceHistoryModels.*;
import com.atlas.modules.corehr.domain.model.Actor;

public interface ReferenceHistoryUseCases {
  Page history(Actor actor, Query query);
}
