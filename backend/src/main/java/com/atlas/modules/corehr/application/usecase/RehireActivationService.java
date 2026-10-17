package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.RehireActivationModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class RehireActivationService implements RehireActivationUseCases {
  private final RehireActivationStore store;
  private final RehireStore rehires;
  private final PeopleStore people;
  private final OrganisationUseCases organisation;
  private final UnitOfWork writes, reads;
  private final Clock clock;

  public RehireActivationService(
      RehireActivationStore store,
      RehireStore rehires,
      PeopleStore people,
      OrganisationUseCases organisation,
      UnitOfWork writes,
      UnitOfWork reads,
      Clock clock) {
    this.store = store;
    this.rehires = rehires;
    this.people = people;
    this.organisation = organisation;
    this.writes = writes;
    this.reads = reads;
    this.clock = clock;
  }

  private Actor hr(String subject) {
    var a = people.actor(subject, LocalDate.now(clock));
    if (!a.hr()) throw new ChangeRequest.Denied();
    return a;
  }

  private Rehire approved(Actor a, String employee) {
    return rehires.history(a.tenant(), employee).stream()
        .filter(r -> r.status().equals("APPROVED"))
        .findFirst()
        .orElseThrow(() -> new ChangeRequest.Conflict("Approve a rehire first."));
  }

  private long version(BigDecimal value) {
    try {
      long v = value.longValueExact();
      if (v < 0) throw new ArithmeticException();
      return v;
    } catch (NullPointerException | ArithmeticException e) {
      throw new IllegalArgumentException("An exact nonnegative employee version is required.");
    }
  }

  private void shape(String reason, String key) {
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw new ChangeRequest.Validation(
          Map.of("reason", "A reason of 1–500 characters is required."));
    if (key == null || !key.matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException("A valid idempotency key is required.");
  }

  private void eligible(Actor a, Employee e, Rehire r) {
    if (a.employeeId().equals(e.id())) throw new ChangeRequest.Denied();
    if (!e.status().equals("Onboarding") || r.startDate().isAfter(LocalDate.now(clock)))
      throw new ChangeRequest.Conflict(
          "Completion requires onboarding on or after the approved rehire start.");
  }

  private void identities(Actor a, String employee, String subject) {
    if (subject == null
        || subject.isBlank()
        || subject.length() > 120
        || !store.identities(a.tenant(), employee).contains(subject))
      throw new ChangeRequest.Validation(
          Map.of(
              "subject",
              "Choose an existing identity mapped to this employee. No new provider account will be created."));
  }

  private void references(Actor a, Employee e) {
    try {
      organisation.requireDepartment(a, e.department(), LocalDate.now(clock));
    } catch (IllegalArgumentException x) {
      throw new ChangeRequest.Validation(
          Map.of(
              "reason", "Current department is not valid; correct the employment record first."));
    }
    if (e.managerId() == null
        || e.managerId().equals(e.id())
        || !people.managerEmployeeIds(a.tenant(), List.of("HR", "MANAGER")).contains(e.managerId())
        || people.employmentEndDate(a.tenant(), e.managerId()).isPresent())
      throw new ChangeRequest.Conflict(
          "Assign a current configured manager without a scheduled end first.");
    var manager = people.employee(a, e.managerId(), LocalDate.now(clock), false);
    if (!Set.of("Active", "On leave").contains(manager.status()))
      throw new ChangeRequest.Conflict("The reporting manager must be active.");
  }

  public Context context(String subject, String employee) {
    return reads.run(
        () -> {
          var a = hr(subject);
          var e = people.employee(a, employee, LocalDate.now(clock), false);
          var r = approved(a, employee);
          return new Context(
              r.id(),
              employee,
              r.startDate(),
              e.status(),
              e.version(),
              store.identities(a.tenant(), employee),
              store.completion(a.tenant(), employee).orElse(null),
              store.history(a.tenant(), employee));
        });
  }

  public RehireActivation submit(String subject, String employee, Submit c, String correlation) {
    long v = version(c.version());
    shape(c.reason(), c.idempotencyKey());
    return writes.run(
        () -> {
          var a = hr(subject);
          people.lockReportingGraph(a.tenant());
          var e = people.employee(a, employee, LocalDate.now(clock), true);
          var r = approved(a, employee);
          if (a.employeeId().equals(employee)) throw new ChangeRequest.Denied();
          var replay = store.duplicate(a.tenant(), subject, c.idempotencyKey(), false);
          if (replay.isPresent()) {
            var old = replay.get();
            if (!old.employeeId().equals(employee)
                || old.inputVersion() != v
                || !old.subject().equals(c.subject())
                || !old.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Key was used for another completion request.");
            return old;
          }
          eligible(a, e, r);
          identities(a, employee, c.subject());
          if (e.version() != v)
            throw new ChangeRequest.Conflict("Employee changed. Refresh completion context.");
          var history = store.history(a.tenant(), employee);
          if (history.size() >= 20
              || history.stream().anyMatch(x -> !x.status().equals("REJECTED")))
            throw new ChangeRequest.Conflict(
                "Completion is pending/approved or the twenty-attempt limit was reached.");
          references(a, e);
          var record =
              new RehireActivation(
                  UUID.randomUUID().toString(),
                  r.id(),
                  employee,
                  c.subject(),
                  c.reason().trim(),
                  v,
                  subject,
                  a.employeeId(),
                  clock.instant().truncatedTo(ChronoUnit.MICROS),
                  correlation,
                  "SUBMITTED",
                  null,
                  null,
                  null,
                  null,
                  null,
                  null);
          store.submit(a.tenant(), record, c.idempotencyKey());
          return record;
        });
  }

  public RehireActivation review(
      String subject, String employee, String id, Review c, String correlation) {
    long v = version(c.version());
    shape(c.reason(), c.idempotencyKey());
    if (!Set.of("APPROVED", "REJECTED").contains(c.decision() == null ? "" : c.decision()))
      throw new IllegalArgumentException("Choose APPROVED or REJECTED.");
    return writes.run(
        () -> {
          var a = hr(subject);
          people.lockReportingGraph(a.tenant());
          var e = people.employee(a, employee, LocalDate.now(clock), true);
          var rehire = approved(a, employee);
          var r =
              store.history(a.tenant(), employee).stream()
                  .filter(x -> x.id().equals(id))
                  .findFirst()
                  .orElseThrow(ChangeRequest.Denied::new);
          if (a.employeeId().equals(employee)
              || a.employeeId().equals(r.actorEmployeeId())
              || subject.equals(r.actor())) throw new ChangeRequest.Denied();
          var replay = store.duplicate(a.tenant(), subject, c.idempotencyKey(), true);
          if (replay.isPresent()) {
            var old = replay.get();
            if (!old.id().equals(id)
                || !old.status().equals(c.decision())
                || old.reviewVersion() != v
                || !old.comment().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Key was used for another completion review.");
            return old;
          }
          if (!r.status().equals("SUBMITTED"))
            throw new ChangeRequest.Conflict("Completion was already reviewed.");
          if (e.version() != v)
            throw new ChangeRequest.Conflict("Employee changed. Refresh context.");
          if (c.decision().equals("APPROVED")) {
            eligible(a, e, rehire);
            if (r.inputVersion() != v)
              throw new ChangeRequest.Conflict(
                  "The employee snapshot changed. Reject and request a fresh review.");
            identities(a, employee, r.subject());
            references(a, e);
          }
          var result =
              new RehireActivation(
                  r.id(),
                  r.rehireId(),
                  employee,
                  r.subject(),
                  r.reason(),
                  r.inputVersion(),
                  r.actor(),
                  r.actorEmployeeId(),
                  r.recordedAt(),
                  r.correlationId(),
                  c.decision(),
                  subject,
                  a.employeeId(),
                  c.reason().trim(),
                  v,
                  clock.instant().truncatedTo(ChronoUnit.MICROS),
                  correlation);
          store.review(
              a.tenant(),
              result,
              c.idempotencyKey(),
              c.decision().equals("APPROVED") ? UUID.randomUUID().toString() : null);
          return result;
        });
  }
}
