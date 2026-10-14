package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.Decide;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;

public final class HiringService implements HiringUseCases {
  private final HiringStore store;
  private final PeopleStore people;
  private final OrganisationUseCases organisation;
  private final UnitOfWork tx;
  private final Clock clock;

  public HiringService(
      HiringStore store,
      PeopleStore people,
      OrganisationUseCases organisation,
      UnitOfWork tx,
      Clock clock) {
    this.store = store;
    this.people = people;
    this.organisation = organisation;
    this.tx = tx;
    this.clock = clock;
  }

  private void requireHr(Actor actor) {
    if (!actor.hr()) throw new ChangeRequest.Denied();
  }

  public HirePage hires(Actor actor, RequestQuery query) {
    requireHr(actor);
    return store.hires(actor.tenant(), query);
  }

  public List<HireAudit> history(Actor actor, String id) {
    requireHr(actor);
    store.hire(actor.tenant(), id, false);
    return store.history(actor.tenant(), id);
  }

  private void validateReferences(Actor actor, HireDetails details) {
    organisation.requireDepartment(actor, details.department(), details.startDate());
    var manager = people.employee(actor, details.managerId(), details.startDate(), false);
    if (manager.startDate().isAfter(details.startDate()))
      throw new ChangeRequest.Validation(
          Map.of("managerId", "The reporting manager must have started by the hire date."));
    if (store.emailExists(actor.tenant(), details.email()))
      throw new ChangeRequest.Conflict(
          "An employee already uses that work email. Review the existing employee record.");
  }

  public HireRequest submit(Actor actor, SubmitHire c, String correlation) {
    requireHr(actor);
    var details =
        new HireDetails(
            c.name(),
            c.email(),
            c.phone(),
            c.title(),
            c.department(),
            c.location(),
            c.managerId(),
            c.startDate());
    if (c.reason() == null || c.reason().isBlank() || c.reason().length() > 500)
      throw new ChangeRequest.Validation(
          Map.of("reason", "A hiring reason of 1–500 characters is required."));
    if (c.idempotencyKey() == null || !c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException("A valid idempotency key is required.");
    return tx.run(
        () -> {
          var previous = store.duplicate(actor.tenant(), actor.subject(), c.idempotencyKey());
          if (previous.isPresent()) {
            var old = previous.get();
            if (!old.details().equals(details) || !old.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Idempotency key was used for another hire.");
            return old;
          }
          var today = LocalDate.now(clock);
          if (details.startDate().isBefore(today)
              || details.startDate().isAfter(today.plusYears(1)))
            throw new ChangeRequest.Validation(
                Map.of("startDate", "Hire start must be today or within the next year."));
          validateReferences(actor, details);
          var request =
              new HireRequest(
                  UUID.randomUUID().toString(),
                  details.name(),
                  details.email(),
                  details.phone(),
                  details.title(),
                  details.department(),
                  details.location(),
                  details.managerId(),
                  details.startDate(),
                  c.reason().trim(),
                  "SUBMITTED",
                  actor.subject(),
                  actor.employeeId(),
                  null,
                  clock.instant(),
                  null,
                  null);
          store.insert(actor.tenant(), request, c.idempotencyKey(), correlation);
          return request;
        });
  }

  public HireRequest decide(Actor actor, String id, Decide c, String correlation) {
    requireHr(actor);
    if (c.comment() == null || c.comment().isBlank() || c.comment().length() > 500)
      throw new ChangeRequest.Validation(
          Map.of("comment", "A decision comment of 1–500 characters is required."));
    return tx.run(
        () -> {
          var request = store.hire(actor.tenant(), id, true);
          request.ensureDecision(actor, c.decision());
          Employee employee = null;
          if (c.decision().equals("APPROVED")) {
            validateReferences(actor, request.details());
            employee = Employee.forHire("E-" + UUID.randomUUID(), request.details());
          }
          store.decide(
              actor.tenant(),
              request,
              actor.subject(),
              c.decision(),
              c.comment().trim(),
              employee,
              clock.instant(),
              correlation);
          return store.hire(actor.tenant(), id, false);
        });
  }
}
