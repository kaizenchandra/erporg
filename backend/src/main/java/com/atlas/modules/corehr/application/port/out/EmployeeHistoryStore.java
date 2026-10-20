package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.EmployeeHistoryModels.*;
import java.util.List;

public interface EmployeeHistoryStore {
  long watermark(Scope scope, Query query);

  long count(Scope scope, Query query, long throughId);

  boolean anchorExists(Scope scope, Query query);

  List<Item> read(Scope scope, Query query, long throughId, int limit);
}
