package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.EndRescheduleModels.*;
import com.atlas.modules.corehr.application.port.in.EndRescheduleUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class EndRescheduleService implements EndRescheduleUseCases {
  private final EndRescheduleStore store;
  private final EmploymentEndStore ends;
  private final PeopleStore people;
  private final UnitOfWork writes, reads;
  private final Clock clock;

  public EndRescheduleService(
      EndRescheduleStore store,
      EmploymentEndStore ends,
      PeopleStore people,
      UnitOfWork writes,
      UnitOfWork reads,
      Clock clock) {
    this.store = store;
    this.ends = ends;
    this.people = people;
    this.writes = writes;
    this.reads = reads;
    this.clock = clock;
  }

  private Actor hr(String subject) {
    var a = people.actor(subject, LocalDate.now(clock));
    if (!a.hr()) throw new ChangeRequest.Denied();
    return a;
  }

  private EmploymentEnd original(Actor a, String id) {
    return ends.get(a.tenant(), id)
        .orElseThrow(
            () -> new ChangeRequest.Conflict("An original employment-end record is required."));
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

  private void date(LocalDate date) {
    if (date == null
        || date.isBefore(LocalDate.now(clock))
        || date.isAfter(LocalDate.now(clock).plusYears(1)))
      throw new ChangeRequest.Validation(
          Map.of(
              "lastWorkingDate",
              "Choose today through one year from today (provisional scheduling limit)."));
  }

  private void beforeCutoff(EmploymentEnd current) {
    if (current != null && current.effectiveOn(LocalDate.now(clock)))
      throw new ChangeRequest.Conflict(
          "The current cutoff has started. Date correction cannot reactivate employment.");
  }

  public Context context(String subject, String id) {
    return reads.run(
        () -> {
          var a = hr(subject);
          var end = original(a, id);
          var e = people.employee(a, end.employeeId(), LocalDate.now(clock), false);
          return new Context(
              ends.current(a.tenant(), id).orElse(null),
              e.version(),
              ends.withdrawals(a.tenant(), id).stream()
                  .anyMatch(w -> w.status().equals("SUBMITTED")),
              store.history(a.tenant(), id));
        });
  }

  public EndReschedule submit(String subject, String id, Submit c, String correlation) {
    long v = version(c.version());
    shape(c.reason(), c.idempotencyKey());
    return writes.run(
        () -> {
          var a = hr(subject);
          people.lockReportingGraph(a.tenant());
          var end = original(a, id);
          var e = people.employee(a, end.employeeId(), LocalDate.now(clock), true);
          if (a.employeeId().equals(e.id())) throw new ChangeRequest.Denied();
          var replay = store.duplicate(a.tenant(), subject, c.idempotencyKey(), false);
          if (replay.isPresent()) {
            var old = replay.get();
            if (!old.requestId().equals(id)
                || !Objects.equals(old.lastWorkingDate(), c.lastWorkingDate())
                || old.inputVersion() != v
                || !old.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Key was used for another replacement request.");
            return old;
          }
          date(c.lastWorkingDate());
          var current = ends.current(a.tenant(), id).orElse(null);
          beforeCutoff(current);
          EmploymentEnd.requireEligible(a, e, EmploymentEnd.inactiveDate(c.lastWorkingDate()));
          if (current != null && current.lastWorkingDate().equals(c.lastWorkingDate()))
            throw new ChangeRequest.Validation(
                Map.of("lastWorkingDate", "Choose a different last working day."));
          if (e.version() != v)
            throw new ChangeRequest.Conflict(
                "Employee changed. Refresh context before requesting.");
          if (ends.withdrawals(a.tenant(), id).stream()
              .anyMatch(w -> w.status().equals("SUBMITTED")))
            throw new ChangeRequest.Conflict("Resolve the pending withdrawal first.");
          var history = store.history(a.tenant(), id);
          if (history.size() >= 20
              || history.stream().anyMatch(r -> !r.status().equals("REJECTED")))
            throw new ChangeRequest.Conflict(
                "A replacement is pending/approved or the 20-attempt limit was reached.");
          var r =
              new EndReschedule(
                  UUID.randomUUID().toString(),
                  id,
                  e.id(),
                  c.lastWorkingDate(),
                  current == null ? null : current.inactiveDate(),
                  e.status(),
                  subject,
                  a.employeeId(),
                  c.reason().trim(),
                  v,
                  clock.instant().truncatedTo(ChronoUnit.MICROS),
                  correlation,
                  "SUBMITTED",
                  null,
                  null,
                  null,
                  null,
                  null,
                  null);
          store.submit(a.tenant(), r, c.idempotencyKey());
          return r;
        });
  }

  public EndReschedule review(
      String subject, String id, String rescheduleId, Review c, String correlation) {
    long v = version(c.version());
    shape(c.reason(), c.idempotencyKey());
    if (!Set.of("APPROVED", "REJECTED").contains(c.decision() == null ? "" : c.decision()))
      throw new IllegalArgumentException("Choose APPROVED or REJECTED.");
    return writes.run(
        () -> {
          var a = hr(subject);
          people.lockReportingGraph(a.tenant());
          var end = original(a, id);
          var e = people.employee(a, end.employeeId(), LocalDate.now(clock), true);
          var r =
              store.history(a.tenant(), id).stream()
                  .filter(x -> x.id().equals(rescheduleId))
                  .findFirst()
                  .orElseThrow(ChangeRequest.Denied::new);
          if (a.employeeId().equals(e.id())
              || a.employeeId().equals(r.actorEmployeeId())
              || subject.equals(r.actor())) throw new ChangeRequest.Denied();
          var replay = store.duplicate(a.tenant(), subject, c.idempotencyKey(), true);
          if (replay.isPresent()) {
            var old = replay.get();
            if (!old.id().equals(rescheduleId)
                || !old.status().equals(c.decision())
                || old.reviewVersion() != v
                || !old.comment().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Key was used for another replacement review.");
            return old;
          }
          if (!r.status().equals("SUBMITTED"))
            throw new ChangeRequest.Conflict("This replacement has already been reviewed.");
          if (e.version() != v)
            throw new ChangeRequest.Conflict("Employee changed. Refresh review context.");
          if (c.decision().equals("APPROVED")) {
            if (r.inputVersion() != v)
              throw new ChangeRequest.Conflict(
                  "The employee snapshot changed. Reject and request a fresh review.");
            date(r.lastWorkingDate());
            var current = ends.current(a.tenant(), id).orElse(null);
            beforeCutoff(current);
            if (!Objects.equals(
                r.priorInactiveDate(), current == null ? null : current.inactiveDate()))
              throw new ChangeRequest.Conflict(
                  "Current schedule changed. Reject and request a fresh review.");
            EmploymentEnd.requireEligible(a, e, EmploymentEnd.inactiveDate(r.lastWorkingDate()));
            if (ends.openTasks(a.tenant(), id, e.id()) > 0)
              throw new ChangeRequest.Conflict(
                  "Resolve all open case and assigned exit tasks before approval.");
            var dates = new TreeSet<LocalDate>();
            var inactive = EmploymentEnd.inactiveDate(r.lastWorkingDate());
            dates.add(inactive);
            dates.addAll(people.reportingDates(a.tenant(), inactive, 257));
            if (dates.size() > 256)
              throw new ChangeRequest.Conflict(
                  "Too many future reporting boundaries to validate safely.");
            for (var boundary : dates)
              if (ends.activeReports(a.tenant(), e.id(), boundary) > 0)
                throw new ChangeRequest.Conflict(
                    "Transfer current/planned reports before approving the replacement.");
          }
          var result =
              new EndReschedule(
                  r.id(),
                  id,
                  e.id(),
                  r.lastWorkingDate(),
                  r.priorInactiveDate(),
                  r.oldStatus(),
                  r.actor(),
                  r.actorEmployeeId(),
                  r.reason(),
                  r.inputVersion(),
                  r.recordedAt(),
                  r.correlationId(),
                  c.decision(),
                  subject,
                  a.employeeId(),
                  c.reason().trim(),
                  v,
                  clock.instant().truncatedTo(ChronoUnit.MICROS),
                  correlation);
          store.review(a.tenant(), result, c.idempotencyKey());
          return result;
        });
  }
}
