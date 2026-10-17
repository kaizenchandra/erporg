package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.ExitTaskModels.*;
import java.util.List;

public interface ExitTaskUseCases {
  Management manage(String subject, String taskId, Manage command, String correlation);

  List<Management> managementHistory(String subject, String taskId);

  Page mine(String subject, Query query);

  Case forCase(String subject, String requestId);

  Item create(String subject, String requestId, Create command, String correlation);

  Item complete(String subject, String taskId, Complete command, String correlation);

  List<Audit> history(String subject, String taskId);
}
