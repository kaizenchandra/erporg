package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.IdentityReplacementModels.*;
import com.atlas.modules.corehr.application.model.RehireAccessModels.Grant;
import com.atlas.modules.corehr.application.port.in.IdentityReplacementUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class IdentityReplacementService implements IdentityReplacementUseCases {
  private final IdentityReplacementStore store;
  private final RehireAccessStore access;
  private final PeopleStore people;
  private final UnitOfWork writes, reads;
  private final Clock clock;

  public IdentityReplacementService(
      IdentityReplacementStore store,
      RehireAccessStore access,
      PeopleStore people,
      UnitOfWork writes,
      UnitOfWork reads,
      Clock clock) {
    this.store = store;
    this.access = access;
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

  private long version(BigDecimal n) {
    try {
      long v = n.longValueExact();
      if (v < 0 || v == Long.MAX_VALUE) throw new ArithmeticException();
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

  private Grant grant(Actor a, String employee) {
    return access
        .grant(a.tenant(), employee)
        .orElseThrow(() -> new ChangeRequest.Conflict("Complete the reviewed rehire first."));
  }

  private void eligible(Actor a, Employee e, Grant g) {
    if (a.employeeId().equals(e.id())) throw new ChangeRequest.Denied();
    if (!e.status().equals("Active")
        || g.grantNumber() != 1
        || access.revocation(a.tenant(), e.id()).isEmpty())
      throw new ChangeRequest.Conflict(
          "An Active completed rehire with its original grant revoked is required. One replacement is supported.");
  }

  private void candidate(Actor a, String employee, String subject) {
    if (subject == null || !store.candidates(a.tenant(), employee).contains(subject))
      throw new ChangeRequest.Validation(
          Map.of(
              "subject",
              "Choose a different unused, unheld identity mapped to this employee. No provider account is created."));
  }

  public Context context(String subject, String employee) {
    return reads.run(
        () -> {
          var a = hr(subject);
          var e = people.employee(a, employee, LocalDate.now(clock), false);
          var g = grant(a, employee);
          var history = store.history(a.tenant(), employee);
          var revoked = access.revocation(a.tenant(), employee).isPresent();
          return new Context(
              employee,
              e.version(),
              e.status(),
              g,
              revoked ? "REVOKED" : "GRANTED",
              access.grants(a.tenant(), employee),
              access.revocations(a.tenant(), employee),
              store.candidates(a.tenant(), employee),
              history,
              !a.employeeId().equals(employee)
                  && e.status().equals("Active")
                  && g.grantNumber() == 1
                  && revoked
                  && history.size() < 20
                  && history.stream().allMatch(r -> r.status().equals("REJECTED")));
        });
  }

  public IdentityReplacement submit(String subject, String employee, Submit c, String correlation) {
    long v = version(c.version());
    shape(c.reason(), c.idempotencyKey());
    return writes.run(
        () -> {
          var a = hr(subject);
          people.lockReportingGraph(a.tenant());
          var e = people.employee(a, employee, LocalDate.now(clock), true);
          if (a.employeeId().equals(employee)) throw new ChangeRequest.Denied();
          var old = store.duplicate(a.tenant(), subject, c.idempotencyKey(), false);
          if (old.isPresent()) {
            var r = old.get();
            if (!r.employeeId().equals(employee)
                || r.inputVersion() != v
                || !r.subject().equals(c.subject())
                || !r.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Key was used for another identity replacement.");
            return r;
          }
          var g = grant(a, employee);
          eligible(a, e, g);
          if (e.version() != v)
            throw new ChangeRequest.Conflict("Employee changed. Refresh replacement context.");
          var history = store.history(a.tenant(), employee);
          if (history.size() >= 20
              || history.stream().anyMatch(r -> !r.status().equals("REJECTED")))
            throw new ChangeRequest.Conflict(
                "Replacement is pending/approved or the twenty-attempt limit was reached.");
          candidate(a, employee, c.subject());
          var r =
              new IdentityReplacement(
                  UUID.randomUUID().toString(),
                  employee,
                  g.activationId(),
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
          store.submit(a.tenant(), r, c.idempotencyKey());
          return r;
        });
  }

  public IdentityReplacement review(
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
          var r =
              store.history(a.tenant(), employee).stream()
                  .filter(x -> x.id().equals(id))
                  .findFirst()
                  .orElseThrow(ChangeRequest.Denied::new);
          if (a.employeeId().equals(employee)
              || a.employeeId().equals(r.actorEmployeeId())
              || subject.equals(r.actor())) throw new ChangeRequest.Denied();
          var old = store.duplicate(a.tenant(), subject, c.idempotencyKey(), true);
          if (old.isPresent()) {
            var o = old.get();
            if (!o.id().equals(id)
                || o.reviewVersion() != v
                || !o.status().equals(c.decision())
                || !o.comment().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Key was used for another replacement review.");
            return o;
          }
          if (!r.status().equals("SUBMITTED"))
            throw new ChangeRequest.Conflict("Replacement was already reviewed.");
          if (e.version() != v)
            throw new ChangeRequest.Conflict("Employee changed. Refresh replacement context.");
          if (c.decision().equals("APPROVED")) {
            var g = grant(a, employee);
            eligible(a, e, g);
            if (!g.activationId().equals(r.predecessorId()) || v != r.inputVersion())
              throw new ChangeRequest.Conflict(
                  "Snapshot changed. Reject and request a fresh review.");
            candidate(a, employee, r.subject());
          }
          var result =
              r.decide(
                  subject,
                  a.employeeId(),
                  c.decision(),
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
