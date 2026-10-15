package com.atlas.modules.benefits.application.usecase;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.in.SelectionsUseCases;
import com.atlas.modules.benefits.application.port.out.*;
import com.atlas.modules.benefits.domain.model.SelectionPolicy;
import com.atlas.shared.domain.*;
import java.time.*;
import java.util.*;

public final class SelectionsService implements SelectionsUseCases {
  private final SelectionStore store;
  private final WorkforcePort workforce;
  private final UnitOfWork tx;
  private final Clock clock;

  public SelectionsService(
      SelectionStore store, WorkforcePort workforce, UnitOfWork tx, Clock clock) {
    this.store = store;
    this.workforce = workforce;
    this.tx = tx;
    this.clock = clock;
  }

  private void text(String value, String field, int min, int max) {
    if (value == null
        || value.trim().length() < min
        || value.length() > max
        || value.chars().anyMatch(Character::isISOControl))
      throw new InputValidation(
          Map.of(field, "Enter " + min + "–" + max + " characters without controls."));
  }

  private void key(String key) {
    if (key == null || !key.matches("[a-zA-Z0-9-]{8,80}"))
      throw new InputValidation(Map.of("idempotencyKey", "A valid command key is required."));
  }

  public SelectionView submit(String subject, SubmitSelection c, String correlation) {
    var actor = workforce.self(subject);
    text(c.planCode(), "planCode", 2, 30);
    text(c.reason(), "reason", 1, 500);
    key(c.idempotencyKey());
    var input =
        new SubmitSelection(
            c.planCode(),
            SelectionPolicy.dependants(c.dependants()),
            c.reason().trim(),
            c.idempotencyKey());
    return tx.run(
        () -> {
          store.lockEmployee(actor.tenant(), actor.employeeId());
          var duplicate = store.duplicate(actor.tenant(), actor.subject(), input.idempotencyKey());
          if (duplicate.isPresent()) {
            var p = duplicate.get();
            if (!p.employeeId().equals(actor.employeeId())) throw new AccessDenied();
            if (!p.planCode().equals(input.planCode())
                || !p.reason().equals(input.reason())
                || !p.dependants().equals(input.dependants()))
              throw new CommandConflict("Selection key was used for different input.");
            return p;
          }
          var plan = store.plan(actor.tenant(), input.planCode()).orElseThrow(AccessDenied::new);
          var date = LocalDate.now(clock);
          var e = plan.eligibility(actor.employmentStatus(), actor.employmentStart(), date);
          if (!e.eligible()) throw new InputValidation(Map.of("planCode", e.explanation()));
          if (store.active(actor.tenant(), actor.employeeId(), plan.code()))
            throw new CommandConflict(
                "A pending or approved selection already exists for this plan.");
          return store.insert(actor, input, plan, date, clock.instant(), correlation);
        });
  }

  public SelectionPage list(String subject, int page, int size) {
    var actor = workforce.self(subject);
    if (page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("Invalid selection page.");
    return store.list(
        actor.tenant(), actor.role().equals("HR") ? null : actor.employeeId(), page, size);
  }

  public SelectionView decide(String subject, String id, DecideSelection c, String correlation) {
    var actor = workforce.self(subject);
    if (!actor.role().equals("HR")) throw new AccessDenied();
    text(c.reason(), "reason", 1, 500);
    key(c.idempotencyKey());
    return tx.run(
        () -> {
          var p = store.locked(actor.tenant(), id).orElseThrow(AccessDenied::new);
          if (p.employeeId().equals(actor.employeeId())) throw new AccessDenied();
          if (c.idempotencyKey().equals(p.decisionKey())) {
            if (!actor.subject().equals(p.decidedBy())
                || !Objects.equals(c.outcome(), p.status())
                || !c.reason().trim().equals(p.decisionReason())
                || c.version() == null
                || c.version() != p.version() - 1)
              throw new CommandConflict("Decision key was used for different input.");
            return p;
          }
          SelectionPolicy.decide(p.status(), p.version(), c.version(), c.outcome());
          if (c.outcome().equals("APPROVED")) {
            var employee = workforce.employee(subject, p.employeeId());
            var plan = store.plan(actor.tenant(), p.planCode()).orElseThrow(AccessDenied::new);
            var e =
                plan.eligibility(
                    employee.employmentStatus(), employee.employmentStart(), p.effectiveDate());
            if (!e.eligible())
              throw new CommandConflict("Employee is no longer eligible: " + e.explanation());
          }
          return store.decision(
              actor.tenant(),
              id,
              actor.subject(),
              new DecideSelection(c.version(), c.outcome(), c.reason().trim(), c.idempotencyKey()),
              clock.instant(),
              correlation);
        });
  }
}
