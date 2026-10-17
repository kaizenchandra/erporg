package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.RehireModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import com.atlas.modules.corehr.domain.service.ReportingHierarchy;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class RehireService implements RehireUseCases {
  private final RehireStore store;
  private final PeopleStore people;
  private final OrganisationUseCases organisation;
  private final UnitOfWork writes, reads;
  private final Clock clock;

  public RehireService(
      RehireStore store,
      PeopleStore people,
      OrganisationUseCases organisation,
      UnitOfWork writes,
      UnitOfWork reads,
      Clock clock) {
    this.store = store;
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

  private LocalDate prior(Actor a, String employee) {
    return people
        .employmentEndDate(a.tenant(), employee)
        .orElseThrow(
            () -> new ChangeRequest.Conflict("A current effective employment end is required."));
  }

  private void date(LocalDate start, LocalDate end) {
    if (start.isBefore(LocalDate.now(clock))
        || start.isAfter(LocalDate.now(clock).plusYears(1))
        || start.isBefore(end))
      throw new ChangeRequest.Validation(
          Map.of(
              "startDate",
              "Choose today through one year from today, on or after the prior inactive date."));
  }

  private void references(Actor a, String employee, RehireDetails d) {
    try {
      organisation.requireDepartment(a, d.department(), d.startDate());
    } catch (IllegalArgumentException invalid) {
      throw new ChangeRequest.Validation(Map.of("department", invalid.getMessage()));
    }
    if (d.managerId().equals(employee)
        || !people.managerEmployeeIds(a.tenant(), List.of("HR", "MANAGER")).contains(d.managerId())
        || people.employmentEndDate(a.tenant(), d.managerId()).isPresent())
      throw new ChangeRequest.Validation(
          Map.of("managerId", "Choose a different HR/manager employee without a scheduled end."));
    var manager = people.employee(a, d.managerId(), d.startDate(), false);
    if (!Set.of("Active", "On leave").contains(manager.status())
        || manager.startDate().isAfter(d.startDate()))
      throw new ChangeRequest.Validation(
          Map.of("managerId", "Choose a manager active by the rehire start date."));
    var dates = new TreeSet<LocalDate>();
    dates.add(d.startDate());
    dates.addAll(people.reportingDates(a.tenant(), d.startDate(), 257));
    if (dates.size() > 256)
      throw new ChangeRequest.Conflict("Too many future reporting boundaries to validate safely.");
    for (var date : dates) {
      var links = people.reportingLinks(a.tenant(), date, 10001);
      if (links.size() > 10000)
        throw new ChangeRequest.Conflict("Reporting validation exceeds 10,000 employees.");
      try {
        ReportingHierarchy.validate(links, employee, d.managerId(), date);
      } catch (ChangeRequest.Validation invalid) {
        var fields = new LinkedHashMap<String, String>();
        invalid
            .fields()
            .forEach(
                (field, message) ->
                    fields.put(field.equals("effectiveDate") ? "startDate" : "managerId", message));
        throw new ChangeRequest.Validation(fields);
      }
    }
  }

  public Context context(String subject, String employee) {
    return reads.run(
        () -> {
          var a = hr(subject);
          var e = people.employee(a, employee, LocalDate.now(clock), false);
          var history = store.history(a.tenant(), employee);
          return new Context(
              employee,
              e.version(),
              e.status(),
              people.employmentEndDate(a.tenant(), employee).orElse(null),
              history.stream().anyMatch(r -> r.status().equals("APPROVED")),
              store.episodes(a.tenant(), employee, LocalDate.now(clock)),
              history);
        });
  }

  public Rehire submit(String subject, String employee, Submit c, String correlation) {
    long v = version(c.version());
    shape(c.reason(), c.idempotencyKey());
    var details =
        new RehireDetails(c.startDate(), c.title(), c.department(), c.location(), c.managerId());
    return writes.run(
        () -> {
          var a = hr(subject);
          people.lockReportingGraph(a.tenant());
          var e = people.employee(a, employee, LocalDate.now(clock), true);
          if (a.employeeId().equals(employee)) throw new ChangeRequest.Denied();
          var replay = store.duplicate(a.tenant(), subject, c.idempotencyKey(), false);
          if (replay.isPresent()) {
            var old = replay.get();
            if (!old.employeeId().equals(employee)
                || old.inputVersion() != v
                || !old.details().equals(details)
                || !old.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Key was used for another rehire.");
            return old;
          }
          Rehire.requireEnded(a, e);
          var end = prior(a, employee);
          date(details.startDate(), end);
          if (e.version() != v)
            throw new ChangeRequest.Conflict("Employee changed. Refresh the rehire context.");
          var history = store.history(a.tenant(), employee);
          if (history.size() >= 20
              || history.stream().anyMatch(r -> !r.status().equals("REJECTED")))
            throw new ChangeRequest.Conflict(
                "A rehire is pending/approved or the twenty-attempt limit was reached.");
          if (store.hasConflictingChanges(a.tenant(), employee, details.startDate()))
            throw new ChangeRequest.Conflict(
                "Resolve pending lifecycle/employee changes and approved future job changes first.");
          references(a, employee, details);
          var r =
              new Rehire(
                  UUID.randomUUID().toString(),
                  employee,
                  details.startDate(),
                  details.title(),
                  details.department(),
                  details.location(),
                  details.managerId(),
                  end,
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

  public Rehire review(String subject, String employee, String id, Review c, String correlation) {
    long v = version(c.version());
    shape(c.reason(), c.idempotencyKey());
    if (!Set.of("APPROVED", "REJECTED").contains(c.decision() == null ? "" : c.decision()))
      throw new IllegalArgumentException("Choose APPROVED or REJECTED.");
    return writes.run(
        () -> {
          var a = hr(subject);
          people.lockReportingGraph(a.tenant());
          var e = people.employee(a, employee, LocalDate.now(clock), true);
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
              throw new ChangeRequest.Conflict("Key was used for another rehire review.");
            return old;
          }
          if (!r.status().equals("SUBMITTED"))
            throw new ChangeRequest.Conflict("This rehire has already been reviewed.");
          if (e.version() != v)
            throw new ChangeRequest.Conflict("Employee changed. Refresh review context.");
          if (c.decision().equals("APPROVED")) {
            Rehire.requireEnded(a, e);
            date(r.startDate(), prior(a, employee));
            if (r.inputVersion() != v || !r.priorInactiveDate().equals(prior(a, employee)))
              throw new ChangeRequest.Conflict(
                  "The prior employment snapshot changed. Reject and request a fresh review.");
            if (store.hasConflictingChanges(a.tenant(), employee, r.startDate()))
              throw new ChangeRequest.Conflict(
                  "Resolve pending changes and old assigned exit work first.");
            references(a, employee, r.details());
          }
          var result =
              new Rehire(
                  r.id(),
                  employee,
                  r.startDate(),
                  r.title(),
                  r.department(),
                  r.location(),
                  r.managerId(),
                  r.priorInactiveDate(),
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
