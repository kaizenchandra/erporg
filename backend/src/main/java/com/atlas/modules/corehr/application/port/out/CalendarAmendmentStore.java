package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.CalendarAmendmentModels.*;
import com.atlas.modules.corehr.domain.model.WorkCalendar;
import java.util.*;

public interface CalendarAmendmentStore {
  List<RevisionEntry> history(String tenant, WorkCalendar base);

  Optional<RevisionEntry> duplicate(String tenant, String actor, String key);

  void append(String tenant, RevisionEntry entry, String key);
}
