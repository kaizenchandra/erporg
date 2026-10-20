package com.atlas.modules.benefits.application.usecase;

import com.atlas.modules.benefits.application.model.PlanHistoryModels.*;
import com.atlas.modules.benefits.application.port.in.PlanHistoryUseCases;
import com.atlas.modules.benefits.application.port.out.*;
import com.atlas.shared.domain.AccessDenied;

public final class PlanHistoryService implements PlanHistoryUseCases {
  private final PlanHistoryStore store;
  private final WorkforcePort workforce;
  private final UnitOfWork reads;

  public PlanHistoryService(PlanHistoryStore store, WorkforcePort workforce, UnitOfWork reads) {
    this.store = store;
    this.workforce = workforce;
    this.reads = reads;
  }

  public Page history(String subject, Query query) {
    return reads.run(
        () -> {
          var context = workforce.self(subject);
          if (!"HR".equals(context.role())) throw new AccessDenied();
          return store.history(context.tenant(), query);
        });
  }
}
