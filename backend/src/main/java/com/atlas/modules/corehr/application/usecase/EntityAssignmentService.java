package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.EntityAssignmentContext;
import com.atlas.modules.corehr.application.port.in.EntityAssignmentUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;

public final class EntityAssignmentService implements EntityAssignmentUseCases {
  private final PeopleStore people;
  private final UnitOfWork reads;
  private final Clock clock;

  public EntityAssignmentService(PeopleStore people, UnitOfWork reads, Clock clock) {
    this.people = people;
    this.reads = reads;
    this.clock = clock;
  }

  public EntityAssignmentContext context(Actor actor, String id) {
    if (!actor.hr()) throw new ChangeRequest.Denied();
    return reads.run(
        () -> {
          var r = people.request(actor, id, false, LocalDate.now(clock));
          if (!r.field().equals("legalEntityCode")) throw new ChangeRequest.Denied();
          return new EntityAssignmentContext(
              id,
              r.newValue().isEmpty()
                  ? null
                  : people
                      .legalEntityForAssignment(actor.tenant(), r.newValue())
                      .orElseThrow(ChangeRequest.Denied::new),
              !r.newValue().isEmpty()
                  && people.legalEntityRetirementBlocked(actor.tenant(), r.newValue()));
        });
  }
}
