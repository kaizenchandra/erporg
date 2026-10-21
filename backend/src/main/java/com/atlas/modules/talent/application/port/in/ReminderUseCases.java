package com.atlas.modules.talent.application.port.in;

import com.atlas.modules.talent.application.model.ReminderModels.*;
import java.util.List;

public interface ReminderUseCases {
  Context context(String subject, int page);

  Policy configure(String subject, Configure command, String correlation);

  Run run(String subject, Command command, String correlation);

  State retry(String subject, Command command, String correlation);

  List<Item> items(String subject, String runId);

  void tick();
}
