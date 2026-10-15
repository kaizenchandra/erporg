package com.atlas.modules.benefits.application.port.in;

import com.atlas.modules.benefits.application.model.*;

public interface SelectionsUseCases {
  SelectionView submit(String subject, SubmitSelection command, String correlation);

  SelectionView decide(String subject, String id, DecideSelection command, String correlation);

  SelectionPage list(String subject, int page, int size);
}
