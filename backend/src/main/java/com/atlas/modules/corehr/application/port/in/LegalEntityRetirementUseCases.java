package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.LegalEntityRetirementModels.*;
import com.atlas.modules.corehr.domain.model.*;
import com.atlas.modules.corehr.domain.model.LegalEntityRetirement.*;

public interface LegalEntityRetirementUseCases {
  InboxPage inbox(Actor actor, InboxQuery query);

  Context context(Actor actor, String code);

  Proposal submit(Actor actor, String code, Submit command, String correlation);

  Decision decide(Actor actor, String code, String requestId, Review command, String correlation);
}
