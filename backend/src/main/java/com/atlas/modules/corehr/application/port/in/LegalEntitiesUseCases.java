package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.LegalEntityModels.*;
import com.atlas.modules.corehr.domain.model.Actor;

public interface LegalEntitiesUseCases {
  Page list(Actor actor, Query query);

  Options options(Actor actor);

  Entry create(Actor actor, Create command, String correlation);
}
