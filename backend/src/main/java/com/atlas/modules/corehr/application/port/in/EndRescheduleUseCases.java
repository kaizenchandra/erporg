package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.EndRescheduleModels.*;
import com.atlas.modules.corehr.domain.model.EndReschedule;

public interface EndRescheduleUseCases {
  Context context(String subject, String requestId);

  EndReschedule submit(String subject, String requestId, Submit command, String correlation);

  EndReschedule review(
      String subject, String requestId, String rescheduleId, Review command, String correlation);
}
