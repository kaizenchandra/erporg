package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.EmploymentEndModels.*;
import com.atlas.modules.corehr.application.port.in.EmploymentEndUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;

public final class EmploymentEndService implements EmploymentEndUseCases {
  private final EmploymentEndStore store;
  private final ResignationStore resignations;
  private final PeopleStore people;
  private final UnitOfWork tx, reads;
  private final Clock clock;

  public EmploymentEndService(
      EmploymentEndStore store,
      ResignationStore resignations,
      PeopleStore people,
      UnitOfWork tx,
      UnitOfWork reads,
      Clock clock) {
    this.store = store;
    this.resignations = resignations;
    this.people = people;
    this.tx = tx;
    this.reads = reads;
    this.clock = clock;
  }

  private Actor hr(String subject) {
    var a = people.actor(subject, LocalDate.now(clock));
    if (!a.hr()) throw new ChangeRequest.Denied();
    return a;
  }

  public Context context(String subject, String id) {
    return reads.run(
        () -> {
          var a = hr(subject);
          var r = resignations.get(a.tenant(), id, false);
          if (!r.status().equals("APPROVED"))
            throw new ChangeRequest.Conflict("Approve the resignation first.");
          var e = people.employee(a, r.employeeId(), LocalDate.now(clock), false);
          var end = store.get(a.tenant(), id).orElse(null);
          var withdrawals = store.withdrawals(a.tenant(), id);
          var current = store.current(a.tenant(), id).orElse(null);
          return new Context(
              end,
              current,
              current != null && !current.equals(end),
              store.replacementReviewOpen(a.tenant(), id),
              end == null
                  ? "NOT_SCHEDULED"
                  : current == null
                      ? "WITHDRAWN"
                      : current.effectiveOn(LocalDate.now(clock)) ? "EFFECTIVE" : "SCHEDULED",
              current == null
                  ? EmploymentEnd.inactiveDate(r.lastWorkingDate())
                  : current.inactiveDate(),
              e.version(),
              store.openTasks(a.tenant(), id, e.id()),
              withdrawals);
        });
  }

  public EmploymentEnd confirm(String subject, String id, Confirm c, String correlation) {
    long v;
    try {
      v = c.version().longValueExact();
      if (v < 0) throw new ArithmeticException();
    } catch (NullPointerException | ArithmeticException e) {
      throw new IllegalArgumentException("An exact nonnegative employee version is required.");
    }
    if (c.reason() == null || c.reason().isBlank() || c.reason().length() > 500)
      throw new ChangeRequest.Validation(
          Map.of("reason", "A confirmation reason of 1–500 characters is required."));
    if (c.idempotencyKey() == null || !c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException("A valid idempotency key is required.");
    return tx.run(
        () -> {
          var a = hr(subject);
          people.lockReportingGraph(a.tenant());
          var preview = resignations.get(a.tenant(), id, false);
          var e = people.employee(a, preview.employeeId(), LocalDate.now(clock), true);
          var r = resignations.get(a.tenant(), id, true);
          if (a.employeeId().equals(e.id())) throw new ChangeRequest.Denied();
          var replay = store.duplicate(a.tenant(), subject, c.idempotencyKey());
          if (replay.isPresent()) {
            var old = replay.get();
            if (!old.requestId().equals(id)
                || old.inputVersion() != v
                || !old.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict(
                  "Idempotency key was used for another employment end.");
            return old;
          }
          if (!r.status().equals("APPROVED"))
            throw new ChangeRequest.Conflict("Approve the resignation first.");
          if (store.get(a.tenant(), id).isPresent())
            throw new ChangeRequest.Conflict(
                "Employment end is already recorded. Refresh the case.");
          var date = EmploymentEnd.inactiveDate(r.lastWorkingDate());
          EmploymentEnd.requireEligible(a, e, date);
          if (e.version() != v)
            throw new ChangeRequest.Conflict(
                "Employee changed. Refresh the case before confirming.");
          if (store.openTasks(a.tenant(), id, e.id()) > 0)
            throw new ChangeRequest.Conflict(
                "Complete/cancel case tasks and reassign/complete this employee's other open exit tasks first.");
          var dates = new TreeSet<LocalDate>();
          dates.add(date);
          dates.addAll(people.reportingDates(a.tenant(), date, 257));
          if (dates.size() > 256)
            throw new ChangeRequest.Conflict(
                "Too many future reporting boundaries to validate safely.");
          for (var asOf : dates)
            if (store.activeReports(a.tenant(), e.id(), asOf) > 0)
              throw new ChangeRequest.Conflict(
                  "Transfer current/planned active reports to another manager before ending employment.");
          var end =
              new EmploymentEnd(
                  id,
                  e.id(),
                  r.lastWorkingDate(),
                  date,
                  subject,
                  a.employeeId(),
                  c.reason().trim(),
                  v,
                  v + 1,
                  e.status(),
                  "Ended",
                  clock.instant(),
                  correlation);
          store.insert(a.tenant(), end, c.idempotencyKey());
          return end;
        });
  }

  private long version(java.math.BigDecimal v) {
    try {
      var n = v.longValueExact();
      if (n < 0) throw new ArithmeticException();
      return n;
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

  public EndWithdrawal withdraw(String subject, String id, Confirm c, String correlation) {
    long v = version(c.version());
    shape(c.reason(), c.idempotencyKey());
    return tx.run(
        () -> {
          var a = hr(subject);
          people.lockReportingGraph(a.tenant());
          var end =
              store
                  .get(a.tenant(), id)
                  .orElseThrow(
                      () -> new ChangeRequest.Conflict("Schedule an employment end first."));
          var e = people.employee(a, end.employeeId(), LocalDate.now(clock), true);
          if (a.employeeId().equals(e.id())) throw new ChangeRequest.Denied();
          var replay = store.withdrawalDuplicate(a.tenant(), subject, c.idempotencyKey(), false);
          if (replay.isPresent()) {
            var old = replay.get();
            if (!old.requestId().equals(id)
                || old.inputVersion() != v
                || !old.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Key was used for another withdrawal.");
            return old;
          }
          if (store.replacementReviewOpen(a.tenant(), id))
            throw new ChangeRequest.Conflict(
                "A replacement review or approved replacement prevents original-schedule withdrawal.");
          if (end.effectiveOn(LocalDate.now(clock)))
            throw new ChangeRequest.Conflict(
                "The cutoff has started; withdrawal cannot reactivate employment.");
          if (e.version() != v)
            throw new ChangeRequest.Conflict(
                "Employee changed. Refresh context before requesting withdrawal.");
          var history = store.withdrawals(a.tenant(), id);
          if (history.size() >= 20
              || history.stream().anyMatch(w -> !w.status().equals("REJECTED")))
            throw new ChangeRequest.Conflict(
                "A withdrawal is pending/approved or the 20-attempt limit was reached.");
          var w =
              new EndWithdrawal(
                  UUID.randomUUID().toString(),
                  id,
                  e.id(),
                  subject,
                  a.employeeId(),
                  c.reason().trim(),
                  v,
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  correlation,
                  "SUBMITTED",
                  null,
                  null,
                  null,
                  null,
                  null,
                  null);
          store.submitWithdrawal(a.tenant(), w, c.idempotencyKey());
          return w;
        });
  }

  public EndWithdrawal reviewWithdrawal(
      String subject, String id, String withdrawalId, ReviewWithdrawal c, String correlation) {
    long v = version(c.version());
    shape(c.reason(), c.idempotencyKey());
    if (!Set.of("APPROVED", "REJECTED").contains(c.decision() == null ? "" : c.decision()))
      throw new IllegalArgumentException("Choose APPROVED or REJECTED.");
    return tx.run(
        () -> {
          var a = hr(subject);
          people.lockReportingGraph(a.tenant());
          var end =
              store
                  .get(a.tenant(), id)
                  .orElseThrow(() -> new ChangeRequest.Conflict("Employment end not found."));
          var e = people.employee(a, end.employeeId(), LocalDate.now(clock), true);
          var w =
              store.withdrawals(a.tenant(), id).stream()
                  .filter(x -> x.id().equals(withdrawalId))
                  .findFirst()
                  .orElseThrow(ChangeRequest.Denied::new);
          if (a.employeeId().equals(e.id())
              || a.employeeId().equals(w.actorEmployeeId())
              || subject.equals(w.actor())) throw new ChangeRequest.Denied();
          var replay = store.withdrawalDuplicate(a.tenant(), subject, c.idempotencyKey(), true);
          if (replay.isPresent()) {
            var old = replay.get();
            if (!old.id().equals(withdrawalId)
                || !old.status().equals(c.decision())
                || old.reviewVersion() != v
                || !old.comment().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Key was used for another review.");
            return old;
          }
          if (!w.status().equals("SUBMITTED"))
            throw new ChangeRequest.Conflict("This withdrawal has already been reviewed.");
          if (e.version() != v)
            throw new ChangeRequest.Conflict("Employee changed. Refresh context before reviewing.");
          if (c.decision().equals("APPROVED")
              && (end.effectiveOn(LocalDate.now(clock)) || w.inputVersion() != v))
            throw new ChangeRequest.Conflict(
                "Approval requires an unchanged employee and a cutoff that has not started. Reject and request a fresh review where permitted.");
          var result =
              new EndWithdrawal(
                  w.id(),
                  id,
                  e.id(),
                  w.actor(),
                  w.actorEmployeeId(),
                  w.reason(),
                  w.inputVersion(),
                  w.recordedAt(),
                  w.correlationId(),
                  c.decision(),
                  subject,
                  a.employeeId(),
                  c.reason().trim(),
                  v,
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  correlation);
          store.reviewWithdrawal(a.tenant(), result, c.idempotencyKey());
          return result;
        });
  }
}
