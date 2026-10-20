package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.CalendarModels.*;
import com.atlas.modules.corehr.domain.model.Actor;
import java.util.List;

public interface CalendarsUseCases {
  Page list(Actor actor, Query query);

  List<String> timeZones(Actor actor);

  Entry create(Actor actor, Create command, String correlation);

  Entry detail(Actor actor, String code);

  Preview preview(Actor actor, String code, String date);
}
