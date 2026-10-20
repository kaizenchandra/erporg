package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.CalendarRetirementModels.Entry;
import java.util.Optional;

public interface CalendarRetirementStore {
  Optional<Entry> find(String tenant, String code);

  Optional<Entry> duplicate(String tenant, String actor, String key);

  void append(String tenant, Entry entry, String key);
}
