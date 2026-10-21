package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.InitialIdentityModels.*;
import com.atlas.modules.corehr.application.port.in.InitialIdentityUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import com.atlas.shared.domain.*;
import java.time.*;
import java.util.*;

public final class InitialIdentityService implements InitialIdentityUseCases {
  private final InitialIdentityStore store;
  private final PeopleStore people;
  private final UnitOfWork tx;
  private final Clock clock;
  private final String issuer;

  public InitialIdentityService(
      InitialIdentityStore s, PeopleStore p, UnitOfWork t, Clock c, String i) {
    store = s;
    people = p;
    tx = t;
    clock = c;
    issuer = i;
  }

  private Actor hr(String s) {
    var a = people.actor(s, LocalDate.now(clock));
    if (!a.hr()) throw new AccessDenied();
    return a;
  }

  private void require(boolean b, String f, String m) {
    if (!b) throw new InputValidation(Map.of(f, m));
  }

  private void input(String reason, String key) {
    require(
        reason != null
            && !reason.isBlank()
            && reason.length() <= 500
            && !reason.chars().anyMatch(Character::isISOControl),
        "reason",
        "Enter 1–500 plain text characters.");
    require(
        key != null && key.matches("[a-zA-Z0-9-]{8,80}"),
        "idempotencyKey",
        "Supply a valid command key.");
  }

  private Instant now() {
    return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  public Context context(String s, String employee) {
    return tx.run(
        () -> {
          var a = hr(s);
          var e = people.employee(a, employee, LocalDate.now(clock), false);
          return new Context(
              issuer, e.version(), e.status(), e.startDate(), store.bindings(a.tenant(), employee));
        });
  }

  public Binding submit(String s, String employee, Submit c, String corr) {
    input(c.reason(), c.idempotencyKey());
    long version;
    try {
      version = c.employeeVersion().longValueExact();
      if (version < 0) throw new ArithmeticException();
    } catch (NullPointerException | ArithmeticException x) {
      throw new InputValidation(
          Map.of("employeeVersion", "Supply an exact nonnegative employee revision."));
    }
    final long v = version;
    require(
        c.federatedSubject() != null
            && c.federatedSubject().matches("[a-zA-Z0-9][a-zA-Z0-9._:@-]{0,119}"),
        "federatedSubject",
        "Enter the exact verified identity-provider subject; never an email lookup.");
    require(
        !issuer.isBlank(),
        "issuer",
        "Configure INITIAL_IDENTITY_ISSUER before binding identities.");
    return tx.run(
        () -> {
          var a = hr(s);
          people.lockReportingGraph(a.tenant());
          store.lock(c.federatedSubject());
          a = hr(s);
          var prior = store.replay(a.tenant(), s, c.idempotencyKey());
          if (prior.isPresent()) {
            var p = prior.get();
            if (!p.employeeId().equals(employee)
                || p.employeeVersion() != v
                || !p.federatedSubject().equals(c.federatedSubject())
                || !p.reason().equals(c.reason().strip())
                || !p.issuer().equals(issuer))
              throw new CommandConflict("Command key already used for different binding data.");
            return new Binding(p, store.decision(a.tenant(), p.id()).orElse(null));
          }
          var e = people.employee(a, employee, LocalDate.now(clock), true);
          if (a.employeeId().equals(employee)) throw new AccessDenied();
          require(
              e.version() == v,
              "employeeVersion",
              "Employee changed. Refresh the binding context.");
          require(
              Set.of("Onboarding", "Active").contains(e.status())
                  && store.eligible(a.tenant(), employee, c.federatedSubject()),
              "federatedSubject",
              "Initial binding requires a new-hire employee with no identity, employment episode or previously held subject. Use the reviewed replacement/rehire workflow otherwise.");
          var r =
              new Request(
                  UUID.randomUUID().toString(),
                  employee,
                  v,
                  c.federatedSubject(),
                  issuer,
                  e.startDate(),
                  s,
                  a.employeeId(),
                  c.reason().strip(),
                  c.idempotencyKey(),
                  now(),
                  corr);
          store.insert(a.tenant(), r);
          return new Binding(r, null);
        });
  }

  public Binding review(String s, String id, Review c, String corr) {
    input(c.reason(), c.idempotencyKey());
    require(
        Set.of("APPROVE", "REJECT").contains(c.decision() == null ? "" : c.decision()),
        "decision",
        "Choose APPROVE or REJECT.");
    return tx.run(
        () -> {
          var a = hr(s);
          people.lockReportingGraph(a.tenant());
          var r = store.find(a.tenant(), id).orElseThrow(AccessDenied::new);
          store.lock(r.federatedSubject());
          a = hr(s);
          if (a.subject().equals(r.preparedBy())
              || a.employeeId().equals(r.preparedEmployee())
              || a.employeeId().equals(r.employeeId())) throw new AccessDenied();
          var prior = store.decision(a.tenant(), id);
          if (prior.isPresent()) {
            var d = prior.get();
            if (d.actor().equals(s)
                && d.idempotencyKey().equals(c.idempotencyKey())
                && d.decision().equals(c.decision())
                && d.reason().equals(c.reason().strip())) return new Binding(r, d);
            throw new CommandConflict("Binding has already been reviewed.");
          }
          var e = people.employee(a, r.employeeId(), LocalDate.now(clock), true);
          if (c.decision().equals("APPROVE")) {
            require(
                r.issuer().equals(issuer) && !issuer.isBlank(),
                "issuer",
                "Configured provider changed; reject and submit a new verified binding.");
            require(
                e.version() == r.employeeVersion() && e.startDate().equals(r.effectiveFrom()),
                "employeeVersion",
                "Employee changed; reject this request and prepare a new one.");
            require(
                Set.of("Onboarding", "Active").contains(e.status())
                    && store.eligible(a.tenant(), e.id(), r.federatedSubject()),
                "federatedSubject",
                "Employee or provider subject is no longer eligible for an initial identity binding.");
          }
          var d =
              new Decision(
                  id,
                  c.decision(),
                  s,
                  a.employeeId(),
                  c.reason().strip(),
                  c.idempotencyKey(),
                  now(),
                  corr);
          store.decide(a.tenant(), r, d, e.name());
          return new Binding(r, d);
        });
  }

  public void verifyIssuer(String subject, String supplied) {
    tx.run(
        () -> {
          var expected = store.issuer(subject);
          if (expected.isPresent() && !expected.get().equals(supplied)) throw new AccessDenied();
          return null;
        });
  }
}
