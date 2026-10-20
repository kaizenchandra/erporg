package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.LegalEntityModels.*;
import java.time.LocalDate;
import java.util.Optional;

public interface LegalEntityStore {
  void lock(String tenant);

  boolean assignmentRecorded(String tenant, String code);

  Optional<Entry> find(String tenant, String code);

  Page list(String tenant, Query query, LocalDate today);

  Optional<Entry> duplicate(String tenant, String actor, String key);

  boolean codeExists(String tenant, String code);

  long count(String tenant);

  void insert(String tenant, Entry entry, String key);
}
