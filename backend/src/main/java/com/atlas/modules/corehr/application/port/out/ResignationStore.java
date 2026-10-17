package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.domain.model.Resignation;
import java.util.*;

public interface ResignationStore {
  record Replay(String key, Long inputVersion) {}

  ResignationPage list(String tenant, String employeeId, RequestQuery query);

  Optional<Resignation> duplicate(String tenant, String subject, String key);

  boolean outstanding(String tenant, String employeeId);

  Resignation get(String tenant, String id, boolean lock);

  Replay decision(String tenant, String id);

  void insert(String tenant, Resignation request, String key, String correlation);

  void decide(
      String tenant, Resignation request, String key, long inputVersion, String correlation);

  List<ResignationAudit> history(String tenant, String id);
}
