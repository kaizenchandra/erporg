package com.atlas.modules.documents.application.port.out;

import com.atlas.modules.documents.application.model.RetentionEvent;
import java.util.*;

public interface DocumentRetentionStore {
  List<RetentionEvent> history(String tenant, String rootId);

  Optional<RetentionEvent> duplicate(String tenant, String actor, String key);

  void append(String tenant, String employeeId, RetentionEvent event, String key);
}
