package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.CalendarRetirementModels.*;
import com.atlas.modules.corehr.domain.model.Actor;

public interface CalendarRetirementUseCases {
  Entry retire(Actor actor, String code, Retire command, String correlation);
}
