package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.port.in.OrganisationUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.List;

public final class OrganisationService implements OrganisationUseCases {
  private final OrganisationStore store;
  private final UnitOfWork tx;
  private final Clock clock;

  public OrganisationService(OrganisationStore store, UnitOfWork tx, Clock clock) {
    this.store = store;
    this.tx = tx;
    this.clock = clock;
  }

  public List<Department> departments(Actor actor) {
    return store.departments(actor.tenant());
  }

  public List<DepartmentAudit> history(Actor actor) {
    if (!actor.hr()) throw new ChangeRequest.Denied();
    return store.history(actor.tenant());
  }

  public Department create(Actor actor, CreateDepartment c, String correlation) {
    if (!actor.hr()) throw new ChangeRequest.Denied();
    var department =
        new Department(c.code(), c.name(), c.parentCode(), c.costCentre(), c.effectiveFrom());
    if (c.reason() == null || c.reason().isBlank() || c.reason().length() > 500)
      throw new IllegalArgumentException("A configuration reason of 1–500 characters is required.");
    if (c.idempotencyKey() == null || !c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException("A valid idempotency key is required.");
    return tx.run(
        () -> {
          var previous = store.duplicate(actor.tenant(), actor.subject(), c.idempotencyKey());
          if (previous.isPresent()) {
            if (!previous
                .get()
                .equals(
                    new CreateDepartment(
                        department.code(),
                        department.name(),
                        department.parentCode(),
                        department.costCentre(),
                        department.effectiveFrom(),
                        c.reason().trim(),
                        c.idempotencyKey())))
              throw new ChangeRequest.Conflict("Idempotency key was used for another department.");
            return department;
          }
          var today = LocalDate.now(clock);
          if (c.effectiveFrom().isBefore(today) || c.effectiveFrom().isAfter(today.plusYears(1)))
            throw new IllegalArgumentException(
                "Department start must be today or within the next year.");
          if (department.parentCode() != null) {
            var parent =
                store
                    .department(actor.tenant(), department.parentCode())
                    .orElseThrow(
                        () ->
                            new IllegalArgumentException(
                                "Choose a parent department in this organisation."));
            if (parent.effectiveFrom().isAfter(department.effectiveFrom()))
              throw new IllegalArgumentException(
                  "The parent must be effective by the department start date.");
          }
          store.insert(
              actor.tenant(),
              actor.subject(),
              department,
              c.reason().trim(),
              c.idempotencyKey(),
              clock.instant(),
              correlation);
          return department;
        });
  }

  public void requireDepartment(Actor actor, String name, LocalDate date) {
    var department =
        store
            .byName(actor.tenant(), name)
            .orElseThrow(() -> new IllegalArgumentException("Choose a configured department."));
    if (department.effectiveFrom().isAfter(date))
      throw new IllegalArgumentException(
          "The selected department is not effective on this change date.");
  }
}
