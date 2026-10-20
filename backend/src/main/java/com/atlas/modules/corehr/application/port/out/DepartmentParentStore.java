package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.DepartmentParentModels.*;
import com.atlas.modules.corehr.domain.model.DepartmentHierarchy.Node;
import java.time.LocalDate;
import java.util.*;

public interface DepartmentParentStore {
  void lock(String tenant);

  Optional<LocalDate> latestDate(String tenant);

  Optional<Snapshot> find(String tenant, String code, LocalDate date);

  List<Node> graph(String tenant, LocalDate date);

  Optional<Change> duplicate(String tenant, String actor, String key);

  List<Change> history(String tenant, String code, int page, int size);

  long total(String tenant, String code);

  void insert(String tenant, Change change);
}
