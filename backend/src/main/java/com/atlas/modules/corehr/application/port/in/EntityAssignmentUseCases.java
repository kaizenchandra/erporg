package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.EntityAssignmentContext;
import com.atlas.modules.corehr.domain.model.Actor;

public interface EntityAssignmentUseCases {
  EntityAssignmentContext context(Actor actor, String requestId);
}
