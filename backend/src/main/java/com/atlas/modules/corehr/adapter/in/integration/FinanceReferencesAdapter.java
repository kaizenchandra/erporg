package com.atlas.modules.corehr.adapter.in.integration;

import com.atlas.modules.corehr.api.FinanceReferenceAccess;
import com.atlas.modules.corehr.application.port.in.FinancialReferenceUseCases;

public final class FinanceReferencesAdapter implements FinanceReferenceAccess {
  private final FinancialReferenceUseCases queries;

  public FinanceReferencesAdapter(FinancialReferenceUseCases queries) {
    this.queries = queries;
  }

  public Entity entity(String subject, String code) {
    var e = queries.entity(subject, code);
    return new Entity(e.code(), e.currency(), e.effectiveFrom(), e.retiredOn());
  }

  public Worker worker(String subject, String id) {
    var w = queries.worker(subject, id);
    return new Worker(w.id(), w.status(), w.start(), w.entityCode());
  }
}
