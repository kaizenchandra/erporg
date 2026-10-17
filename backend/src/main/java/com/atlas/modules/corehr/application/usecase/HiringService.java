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

  public OnboardingCompletion completeOnboarding(
      Actor actor, String id, CompleteOnboarding c, String correlation) {
    requireHr(actor);
    if (c.reason() == null || c.reason().isBlank() || c.reason().length() > 500)
      throw new ChangeRequest.Validation(
          Map.of("reason", "An onboarding completion reason of 1–500 characters is required."));
    if (c.version() == null
        || c.version() < 0
        || c.idempotencyKey() == null
        || !c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException(
          "A current employee version and valid idempotency key are required.");
    return tx.run(
        () -> {
          var hire = store.hire(actor.tenant(), id, true);
          var replay = store.completionReplay(actor.tenant(), actor.subject(), c.idempotencyKey());
          if (replay.isPresent()) {
            var previous = replay.get();
            if (!previous.hireId().equals(id)
                || previous.inputVersion() != c.version()
                || !previous.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict(
                  "Idempotency key was used for a different completion.");
            return previous;
          }
          if (!hire.status().equals("APPROVED") || hire.employeeId() == null)
            throw new ChangeRequest.Conflict("Approve the hire before completing onboarding.");
          if (store.completion(actor.tenant(), id).isPresent())
            throw new ChangeRequest.Conflict(
                "Onboarding was already completed. Refresh the employee profile.");
          var employee = people.employee(actor, hire.employeeId(), LocalDate.now(clock), true);
          if (employee.version() != c.version())
            throw new ChangeRequest.Conflict(
                "The employee changed. Refresh the profile before completing onboarding.");
          var active = employee.completeOnboarding(LocalDate.now(clock));
          var completion =
              new OnboardingCompletion(
                  id,
                  employee.id(),
                  actor.subject(),
                  c.reason().trim(),
                  c.version(),
                  active.version(),
                  employee.status(),
                  active.status(),
                  LocalDate.now(clock),
                  clock.instant(),
                  correlation);
          store.completeOnboarding(actor.tenant(), completion, active, c.idempotencyKey());
          return completion;
        });
  }

  public List<OnboardingCompletion> employmentHistory(Actor actor, String employeeId) {
    var employee = people.employee(actor, employeeId, LocalDate.now(clock), false);
    if (!actor.canRead(employee)) throw new ChangeRequest.Denied();
    return store.employmentHistory(actor.tenant(), employeeId).stream()
        .map(
            c ->
                actor.canReadField(employee, "address")
                    ? c
                    : new OnboardingCompletion(
                        c.hireId(),
                        c.employeeId(),
                        c.actor(),
                        null,
                        c.inputVersion(),
                        c.newVersion(),
                        c.oldStatus(),
                        c.newStatus(),
                        c.effectiveDate(),
                        c.recordedAt(),
                        c.correlationId()))
        .toList();
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
    if (people.employmentEndDate(actor.tenant(), manager.id()).isPresent())
      throw new ChangeRequest.Validation(
          Map.of("managerId", "Choose a manager without a scheduled employment end."));
    if (manager.status().equals("Ended"))
      throw new ChangeRequest.Validation(
          Map.of("managerId", "Choose a manager active on the hire start date."));
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
          people.lockReportingGraph(actor.tenant());
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
