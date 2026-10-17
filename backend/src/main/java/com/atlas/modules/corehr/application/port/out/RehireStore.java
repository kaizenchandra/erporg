package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.RehireModels.Episode;
import com.atlas.modules.corehr.domain.model.Rehire;
import java.time.LocalDate;
import java.util.*;

public interface RehireStore {
  List<Rehire> history(String tenant, String employeeId);

  List<Episode> episodes(String tenant, String employeeId, LocalDate asOf);

  Optional<Rehire> duplicate(String tenant, String actor, String key, boolean review);

  boolean hasConflictingChanges(String tenant, String employeeId, LocalDate start);

  void submit(String tenant, Rehire record, String key);

  void review(String tenant, Rehire record, String key);
}
