package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.ExitTaskModels.*;
import com.atlas.modules.corehr.domain.model.ExitTask;
import java.util.*;

public interface ExitTaskStore {
  record Rows(List<ExitTask> items, long total) {}

  record Replay(ExitTask task, long inputVersion) {}

  Optional<Management> managementReplay(String tenant, String subject, String key);

  List<Management> managementHistory(String tenant, String taskId);

  void manage(String tenant, Management event, String key);

  Rows mine(String tenant, String assignee, Query query);

  List<ExitTask> forCase(String tenant, String requestId);

  Optional<Replay> duplicate(String tenant, String subject, String key);

  ExitTask task(String tenant, String id, boolean lock);

  String completionKey(String tenant, String id);

  void insert(String tenant, ExitTask task, String key, long inputVersion, String correlation);

  void complete(String tenant, ExitTask task, String key, String correlation);

  List<Audit> history(String tenant, String taskId);
}
