package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.PositionDirectoryModels.*;
import com.atlas.modules.corehr.application.port.in.PositionDirectoryUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.Position;
import java.time.*;

public final class PositionDirectoryService implements PositionDirectoryUseCases {
  private final PeopleStore people;
  private final PositionDirectoryStore store;
  private final UnitOfWork reads;
  private final Clock clock;

  public PositionDirectoryService(
      PeopleStore people, PositionDirectoryStore store, UnitOfWork reads, Clock clock) {
    this.people = people;
    this.store = store;
    this.reads = reads;
    this.clock = clock;
  }

  public Page browse(String subject, Query query) {
    return reads.run(
        () -> {
          var date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
          var actor = people.actor(subject, date);
          var result = store.browse(actor.tenant(), query, date);
          return new Page(
              result.items().stream()
                  .map(
                      p ->
                          new Item(
                              p.code(),
                              p.title(),
                              p.departmentCode(),
                              p.departmentName(),
                              p.effectiveFrom(),
                              new Position(
                                          p.code(),
                                          p.title(),
                                          p.departmentCode(),
                                          p.effectiveFrom())
                                      .currentOn(date)
                                  ? "CURRENT"
                                  : "PLANNED"))
                  .toList(),
              result.total(),
              query.page(),
              query.size(),
              date);
        });
  }
}
