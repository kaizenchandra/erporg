package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.DepartmentDirectoryModels.*;
import com.atlas.modules.corehr.application.port.in.DepartmentDirectoryUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.Actor;
import java.time.*;

public final class DepartmentDirectoryService implements DepartmentDirectoryUseCases {
  private final DepartmentDirectoryStore store;
  private final UnitOfWork reads;
  private final Clock clock;

  public DepartmentDirectoryService(DepartmentDirectoryStore store, UnitOfWork reads, Clock clock) {
    this.store = store;
    this.reads = reads;
    this.clock = clock;
  }

  public Page browse(Actor actor, Query query) {
    return reads.run(
        () ->
            store.browse(
                actor.tenant(), query, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)));
  }
}
