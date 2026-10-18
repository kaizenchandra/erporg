package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.RehireAccessModels.*;
import com.atlas.modules.corehr.application.port.in.RehireAccessUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.time.temporal.ChronoUnit;

public final class RehireAccessService implements RehireAccessUseCases {
  private final RehireAccessStore store;
  private final PeopleStore people;
  private final UnitOfWork writes, reads;
  private final Clock clock;

  public RehireAccessService(
      RehireAccessStore store,
      PeopleStore people,
      UnitOfWork writes,
      UnitOfWork reads,
      Clock clock) {
    this.store = store;
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

  public Context context(String subject, String employee) {
    return reads.run(
        () -> {
          var a = hr(subject);
          var e = people.employee(a, employee, LocalDate.now(clock), false);
          var grant =
              store
                  .grant(a.tenant(), employee)
                  .orElseThrow(
                      () -> new ChangeRequest.Conflict("Complete the reviewed rehire first."));
          var r = store.revocation(a.tenant(), employee).orElse(null);
          return new Context(
              employee,
              e.version(),
              e.status(),
              grant.activationId(),
              grant.subject(),
              grant.role(),
              r == null ? "GRANTED" : "REVOKED",
              grant.grantNumber(),
              r);
        });
  }

  public RehireAccessRevocation revoke(
      String subject, String employee, Revoke c, String correlation) {
    long version;
    try {
      version = c.version().longValueExact();
      if (version < 0 || version == Long.MAX_VALUE) throw new ArithmeticException();
    } catch (NullPointerException | ArithmeticException x) {
      throw new IllegalArgumentException("An exact nonnegative employee version is required.");
    }
    if (c.reason() == null || c.reason().isBlank() || c.reason().length() > 500)
      throw new ChangeRequest.Validation(
          java.util.Map.of("reason", "A reason of 1–500 characters is required."));
    if (c.idempotencyKey() == null || !c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException("A valid idempotency key is required.");
    return writes.run(
        () -> {
          var a = hr(subject);
          people.lockReportingGraph(a.tenant());
          var e = people.employee(a, employee, LocalDate.now(clock), true);
          if (a.employeeId().equals(employee)) throw new ChangeRequest.Denied();
          var old = store.duplicate(a.tenant(), subject, c.idempotencyKey());
          if (old.isPresent()) {
            var r = old.get();
            if (!r.employeeId().equals(employee)
                || !r.activationId().equals(c.activationId())
                || r.inputVersion() != version
                || !r.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Key was used for another access revocation.");
            return r;
          }
          var grant =
              store
                  .grant(a.tenant(), employee)
                  .orElseThrow(
                      () -> new ChangeRequest.Conflict("No completed local rehire grant exists."));
          if (!grant.activationId().equals(c.activationId()))
            throw new ChangeRequest.Conflict("Grant changed. Refresh access context.");
          if (store.revocation(a.tenant(), employee).isPresent())
            throw new ChangeRequest.Conflict("Local access was already revoked.");
          if (e.version() != version)
            throw new ChangeRequest.Conflict(
                "Employee changed. Refresh access context before revoking.");
          var r =
              new RehireAccessRevocation(
                  grant.activationId(),
                  employee,
                  grant.subject(),
                  version,
                  version + 1,
                  subject,
                  a.employeeId(),
                  c.reason().trim(),
                  clock.instant().truncatedTo(ChronoUnit.MICROS),
                  correlation);
          store.revoke(a.tenant(), r, c.idempotencyKey());
          return r;
        });
  }
}
