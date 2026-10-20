package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.CalendarModels.*;
import java.time.LocalDate;
import java.util.Optional;

public interface CalendarStore {
  void lock(String tenant);

  boolean assignmentRecorded(String tenant, String code);

  Optional<Entry> find(String tenant, String code);

  Optional<Entry> duplicate(String tenant, String actor, String key);

  long count(String tenant);

  Page list(String tenant, Query query, LocalDate date);

  void insert(String tenant, Entry entry, String key);
}
