package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.ReferenceHistoryModels.*;
import com.atlas.modules.corehr.application.port.in.ReferenceHistoryUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;

public final class ReferenceHistoryService implements ReferenceHistoryUseCases {
  private final ReferenceHistoryStore store;
  private final UnitOfWork reads;

  public ReferenceHistoryService(ReferenceHistoryStore store, UnitOfWork reads) {
    this.store = store;
    this.reads = reads;
  }

  public Page history(Actor actor, Query query) {
    if (!actor.hr()) throw new ChangeRequest.Denied();
    return reads.run(() -> store.history(actor.tenant(), query));
  }
}
