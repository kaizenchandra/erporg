package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.CalendarAmendmentModels.*;
import com.atlas.modules.corehr.domain.model.Actor;

public interface CalendarAmendmentsUseCases {
  Context context(Actor actor, String code);

  RevisionEntry amend(Actor actor, String code, Amend command, String correlation);
}
