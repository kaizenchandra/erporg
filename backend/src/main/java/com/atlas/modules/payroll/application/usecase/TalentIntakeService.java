package com.atlas.modules.payroll.application.usecase;

import com.atlas.modules.payroll.api.TalentIntakeAccess.*;
import com.atlas.modules.payroll.application.port.in.TalentIntakeUseCases;
import com.atlas.modules.payroll.application.port.out.PayrollPorts.Transactions;
import com.atlas.modules.payroll.application.port.out.TalentIntakePorts.*;
import com.atlas.shared.domain.*;
import java.time.*;
import java.util.*;

public final class TalentIntakeService implements TalentIntakeUseCases {
  private final Store db;
  private final IdentityAccess env;
  private final Transactions tx;
  private final Clock clock;

  public TalentIntakeService(Store db, IdentityAccess env, Transactions tx, Clock clock) {
    this.db = db;
    this.env = env;
    this.tx = tx;
    this.clock = clock;
  }

  private Identity hr(String s) {
    var a = env.self(s);
    if (!a.role().equals("HR")) throw new AccessDenied();
    return a;
  }

  public String receive(String s, Request r, String reason, String corr) {
    return tx.run(
        () -> {
          var a = hr(s);
          if (r == null
              || r.sourceId() == null
              || !r.sourceId().matches("[a-zA-Z0-9-]{8,40}")
              || r.currency() == null
              || !Set.of("USD", "EUR", "GBP", "INR").contains(r.currency())
              || reason == null
              || reason.isBlank()
              || reason.length() > 1000)
            throw new InputValidation(
                Map.of("source", "Supply a valid approved compensation source and reason."));
          db.lock(a.tenant(), r.sourceId());
          if (!a.equals(hr(s))) throw new CommandConflict("Identity changed. Reload.");
          if (a.subject().equals(r.preparedBy()) || a.employeeId().equals(r.preparedEmployee()))
            throw new AccessDenied();
          if (r.awards() == null || r.awards().isEmpty() || r.awards().size() > 50)
            throw new InputValidation(
                Map.of("awards", "Supply a bounded approved award snapshot."));
          var ids = new HashSet<String>();
          for (var w : r.awards()) {
            if (w == null
                || w.employeeId() == null
                || !ids.add(w.employeeId())
                || w.currentSalary() == null
                || w.proposedSalary() == null
                || w.bonus() == null
                || w.effectiveDate() == null
                || w.currentSalary().signum() < 0
                || w.proposedSalary().compareTo(w.currentSalary()) < 0
                || w.bonus().signum() < 0
                || w.currentSalary().scale() > 2
                || w.proposedSalary().scale() > 2
                || w.bonus().scale() > 2)
              throw new InputValidation(
                  Map.of(
                      "awards",
                      "Supply unique employees and valid exact-decimal award snapshots."));
            env.worker(s, w.employeeId());
          }
          var prior = db.find(a.tenant(), r.sourceId());
          if (prior.isPresent()) {
            var p = prior.get();
            if (!p.currency().equals(r.currency()) || !p.awards().equals(r.awards()))
              throw new CommandConflict(
                  "Compensation source already received with different values.");
            return p.id();
          }
          var i =
              new Intake(
                  UUID.randomUUID().toString(),
                  r.sourceId(),
                  r.currency(),
                  List.copyOf(r.awards()),
                  "RECEIVED",
                  s,
                  clock.instant());
          db.insert(a.tenant(), i, reason, corr);
          return i.id();
        });
  }

  public List<Intake> pending(String s, int page) {
    if (page < 0 || page > 10000) throw new InputValidation(Map.of("page", "Use a bounded page."));
    return tx.run(() -> db.pending(hr(s).tenant(), page));
  }
}
