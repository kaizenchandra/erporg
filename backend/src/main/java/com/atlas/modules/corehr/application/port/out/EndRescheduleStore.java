package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.domain.model.EndReschedule;
import java.util.*;

public interface EndRescheduleStore {
  List<EndReschedule> history(String tenant, String requestId);

  Optional<EndReschedule> duplicate(String tenant, String actor, String key, boolean review);

  void submit(String tenant, EndReschedule record, String key);

  void review(String tenant, EndReschedule record, String key);
}
