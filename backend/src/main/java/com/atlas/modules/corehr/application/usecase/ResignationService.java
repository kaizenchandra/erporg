package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.port.in.ResignationUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class ResignationService implements ResignationUseCases {
  private final ResignationStore store;
  private final PeopleStore people;
  private final UnitOfWork tx;
  private final UnitOfWork reads;
  private final Clock clock;

  public ResignationService(
      ResignationStore store, PeopleStore people, UnitOfWork tx, UnitOfWork reads, Clock clock) {
    this.store = store;
    this.people = people;
    this.tx = tx;
    this.reads = reads;
    this.clock = clock;
  }

  private Actor actor(String subject) {
    return people.actor(subject, LocalDate.now(clock));
  }

  private void access(Actor actor, Resignation r) {
    if (!actor.hr() && !actor.employeeId().equals(r.employeeId())) throw new ChangeRequest.Denied();
  }

  private long version(BigDecimal value) {
    try {
      if (value != null && value.signum() >= 0) return value.longValueExact();
    } catch (ArithmeticException ignored) {
    }
    throw new IllegalArgumentException("An exact nonnegative version is required.");
  }

  private void key(String key) {
    if (key == null || !key.matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException("A valid idempotency key is required.");
  }

  public ResignationPage list(String subject, RequestQuery query) {
    return reads.run(
        () -> {
          var a = actor(subject);
          return store.list(a.tenant(), a.hr() ? null : a.employeeId(), query);
        });
  }

  public List<ResignationAudit> history(String subject, String id) {
    return reads.run(
        () -> {
          var a = actor(subject);
          access(a, store.get(a.tenant(), id, false));
          return store.history(a.tenant(), id);
        });
  }

  public CaseEpisode episode(String subject, String id) {
    return reads.run(
        () -> {
          var a = actor(subject);
          access(a, store.get(a.tenant(), id, false));
          return store.episode(a.tenant(), id);
        });
  }

  public Resignation submit(String subject, ResignationCommands.Submit c, String correlation) {
    Resignation.validate(c.lastWorkingDate(), c.reason());
    long v = version(c.employeeVersion());
    key(c.idempotencyKey());
    return tx.run(
        () -> {
          var a = actor(subject);
          var employee = people.employee(a, a.employeeId(), LocalDate.now(clock), true);
          var old = store.duplicate(a.tenant(), subject, c.idempotencyKey());
          if (old.isPresent()) {
            var r = old.get();
            if (!r.lastWorkingDate().equals(c.lastWorkingDate())
                || !r.reason().equals(c.reason().trim())
                || r.employeeVersion() != v)
              throw new ChangeRequest.Conflict("Idempotency key was used for another resignation.");
            return r;
          }
          if (employee.version() != v)
            throw new ChangeRequest.Conflict(
                "The employee changed. Refresh your profile and submit again.");
          Resignation.eligible(employee, c.lastWorkingDate(), LocalDate.now(clock));
          if (store.outstanding(a.tenant(), employee.id()))
            throw new ChangeRequest.Conflict(
                "An outstanding resignation already exists. Review it before submitting another.");
          var episode =
              EmploymentEpisodeRef.originalForCase(
                  store.episodes(a.tenant(), employee.id()), LocalDate.now(clock));
          var r =
              new Resignation(
                  UUID.randomUUID().toString(),
                  employee.id(),
                  c.lastWorkingDate(),
                  c.reason().trim(),
                  "SUBMITTED",
                  subject,
                  v,
                  clock.instant(),
                  0,
                  null,
                  null,
                  null,
                  null);
          store.insert(a.tenant(), r, episode, c.idempotencyKey(), correlation);
          return r;
        });
  }

  public Optional<ResignationWithdrawal> withdrawal(String subject, String id) {
    return reads.run(
        () -> {
          var a = actor(subject);
          access(a, store.get(a.tenant(), id, false));
          return store.withdrawal(a.tenant(), id);
        });
  }

  public ResignationWithdrawal withdraw(
      String subject, String id, ResignationCommands.Withdraw c, String correlation) {
    long v = version(c.version());
    key(c.idempotencyKey());
    if (c.reason() == null || c.reason().isBlank() || c.reason().length() > 500)
      throw new ChangeRequest.Validation(
          Map.of("reason", "A withdrawal reason of 1–500 characters is required."));
    return tx.run(
        () -> {
          var a = actor(subject);
          var initial = store.get(a.tenant(), id, false);
          if (!a.subject().equals(initial.requestedBy())
              || !a.employeeId().equals(initial.employeeId())) throw new ChangeRequest.Denied();
          people.employee(a, initial.employeeId(), LocalDate.now(clock), true);
          var r = store.get(a.tenant(), id, true);
          var prior = store.withdrawalDuplicate(a.tenant(), subject, c.idempotencyKey());
          if (prior.isPresent()) {
            var old = prior.get();
            if (!old.requestId().equals(id)
                || old.inputVersion() != v
                || !old.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Key was used for another resignation withdrawal.");
            return old;
          }
          var receipt =
              ResignationWithdrawal.withdraw(
                  r,
                  a,
                  c.reason(),
                  v,
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  correlation);
          store.withdraw(a.tenant(), receipt, c.idempotencyKey());
          return receipt;
        });
  }

  public Resignation review(
      String subject, String id, ResignationCommands.Review c, String correlation) {
    long v = version(c.version());
    key(c.idempotencyKey());
    if (c.comment() == null || c.comment().isBlank() || c.comment().length() > 500)
      throw new ChangeRequest.Validation(
          Map.of("comment", "A decision comment of 1–500 characters is required."));
    return tx.run(
        () -> {
          var a = actor(subject);
          if (!a.hr()) throw new ChangeRequest.Denied();
          var initial = store.get(a.tenant(), id, false);
          var employee = people.employee(a, initial.employeeId(), LocalDate.now(clock), true);
          var r = store.get(a.tenant(), id, true);
          if (subject.equals(r.requestedBy()) || a.employeeId().equals(r.employeeId()))
            throw new ChangeRequest.Denied();
          var replay = store.decision(a.tenant(), id);
          if (c.idempotencyKey().equals(replay.key())) {
            if (!subject.equals(r.decidedBy())
                || !Objects.equals(replay.inputVersion(), v)
                || !Objects.equals(c.decision(), r.status())
                || !c.comment().trim().equals(r.comment()))
              throw new ChangeRequest.Conflict("Idempotency key was used for another decision.");
            return r;
          }
          r.ensureReview(a, c.decision(), v);
          if (c.decision().equals("APPROVED")) {
            if (employee.version() != r.employeeVersion())
              throw new ChangeRequest.Conflict(
                  "The employee changed. Reject this request and submit a fresh resignation.");
            var original =
                EmploymentEpisodeRef.originalForCase(
                    store.episodes(a.tenant(), employee.id()), LocalDate.now(clock));
            if (store.episode(a.tenant(), id).episodeNumber() != original.number())
              throw new ChangeRequest.Conflict("Case episode changed; review its provenance.");
            Resignation.eligible(employee, r.lastWorkingDate(), LocalDate.now(clock));
          }
          var decided =
              new Resignation(
                  r.id(),
                  r.employeeId(),
                  r.lastWorkingDate(),
                  r.reason(),
                  c.decision(),
                  r.requestedBy(),
                  r.employeeVersion(),
                  r.recordedAt(),
                  r.version() + 1,
                  subject,
                  a.employeeId(),
                  c.comment().trim(),
                  clock.instant());
          store.decide(a.tenant(), decided, c.idempotencyKey(), v, correlation);
          return decided;
        });
  }
}
