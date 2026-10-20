package com.atlas.modules.benefits.application.usecase;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.in.SelectionInboxUseCases;
import com.atlas.modules.benefits.application.port.out.*;
import com.atlas.shared.domain.AccessDenied;

public final class SelectionInboxService implements SelectionInboxUseCases {
  private final SelectionInboxStore store;
  private final WorkforcePort workforce;
  private final UnitOfWork reads;

  public SelectionInboxService(
      SelectionInboxStore store, WorkforcePort workforce, UnitOfWork reads) {
    this.store = store;
    this.workforce = workforce;
    this.reads = reads;
  }

  public SelectionPage inbox(String subject, SelectionInboxQuery query) {
    return reads.run(
        () -> {
          var actor = workforce.self(subject);
          boolean review = query.scope().equals("REVIEW");
          if (review && !actor.role().equals("HR")) throw new AccessDenied();
          var scope =
              review
                  ? new SelectionInboxScope(
                      actor.tenant(), null, actor.employeeId(), actor.subject())
                  : new SelectionInboxScope(actor.tenant(), actor.employeeId(), null, null);
          return store.inbox(scope, query);
        });
  }
}
