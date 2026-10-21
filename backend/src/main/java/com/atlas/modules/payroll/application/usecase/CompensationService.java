package com.atlas.modules.payroll.application.usecase;

import com.atlas.modules.payroll.api.*;
import com.atlas.modules.payroll.api.CompensationAccess.Salary;
import com.atlas.modules.payroll.application.model.CompensationModels.*;
import com.atlas.modules.payroll.application.model.PayrollModels.Source;
import com.atlas.modules.payroll.application.port.in.*;
import com.atlas.modules.payroll.application.port.out.CompensationPorts.*;
import com.atlas.modules.payroll.application.port.out.PayrollPorts.Transactions;
import com.atlas.modules.payroll.domain.model.CompensationCalculation;
import com.atlas.shared.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;

public final class CompensationService implements CompensationUseCases {
  private final Store db;
  private final Environment env;
  private final Transactions tx;
  private final Clock clock;

  public CompensationService(Store d, Environment e, Transactions t, Clock c) {
    db = d;
    env = e;
    tx = t;
    clock = c;
  }

  private Identity hr(String s) {
    var a = env.self(s);
    if (!a.role().equals("HR")) throw new AccessDenied();
    return a;
  }

  private void require(boolean b, String f, String m) {
    if (!b) throw new InputValidation(Map.of(f, m));
  }

  private Instant now() {
    return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
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

  public State state(String s, String e) {
    return tx.run(
        () -> {
          var a = hr(s);
          env.worker(s, e);
          return new State(db.setups(a.tenant(), e), db.salaries(a.tenant(), e));
        });
  }

  public Salary salary(String s, String e, LocalDate date) {
    return tx.run(
        () -> {
          var a = hr(s);
          env.worker(s, e);
          require(date != null, "date", "Supply an effective date.");
          return db.salary(a.tenant(), e, date)
              .orElseThrow(
                  () ->
                      new CommandConflict(
                          "No approved effective compensation exists for this worker and date. Establish the independently reviewed payroll baseline first."));
        });
  }

  public Setup propose(String s, Baseline c, String corr) {
    input(c.reason(), c.idempotencyKey());
    CompensationCalculation.hourly(c.annualSalary(), c.annualHours());
    require(
        Set.of("USD", "EUR", "GBP", "INR").contains(c.currency() == null ? "" : c.currency()),
        "currency",
        "Choose USD, EUR, GBP or INR. Payroll calculation currently supports the demonstration USD ledger only.");
    return tx.run(
        () -> {
          var a = hr(s);
          env.worker(s, c.employeeId());
          db.lock(a.tenant(), c.employeeId());
          var prior = db.replay(a.tenant(), s, c.idempotencyKey());
          if (prior.isPresent()) {
            if (!prior.get().input().equals(c))
              throw new CommandConflict("Command key already used for different baseline data.");
            return prior.get();
          }
          var w = env.worker(s, c.employeeId());
          require(
              Set.of("Active", "On leave").contains(w.status()),
              "employeeId",
              "An active employment episode is required.");
          require(
              c.effectiveDate() != null
                  && !c.effectiveDate().isBefore(w.start())
                  && !c.effectiveDate().isAfter(LocalDate.now(clock)),
              "effectiveDate",
              "Choose a date within the started employment episode, no later than today.");
          require(
              db.salaries(a.tenant(), c.employeeId()).isEmpty(),
              "employeeId",
              "An approved baseline already exists. Use a reviewed talent compensation plan for changes.");
          var r =
              new Setup(
                  UUID.randomUUID().toString(),
                  c,
                  w.start(),
                  s,
                  a.employeeId(),
                  "SUBMITTED",
                  null,
                  null,
                  null,
                  now());
          db.setup(a.tenant(), r, corr);
          return r;
        });
  }

  public Setup decide(String s, String id, Decision c, String corr) {
    input(c.reason(), c.idempotencyKey());
    require(
        Set.of("APPROVE", "REJECT").contains(c.action() == null ? "" : c.action()),
        "action",
        "Choose APPROVE or REJECT.");
    return tx.run(
        () -> {
          var a = hr(s);
          var r = db.setup(a.tenant(), id).orElseThrow(AccessDenied::new);
          db.lock(a.tenant(), r.input().employeeId());
          r = db.setup(a.tenant(), id).orElseThrow(AccessDenied::new);
          if (a.subject().equals(r.preparedBy())
              || a.employeeId().equals(r.preparedEmployee())
              || a.employeeId().equals(r.input().employeeId())) throw new AccessDenied();
          var next = c.action().equals("APPROVE") ? "APPROVED" : "REJECTED";
          if (!r.status().equals("SUBMITTED")) {
            if (r.status().equals(next)
                && s.equals(r.reviewedBy())
                && c.reason().equals(r.reviewReason())) return r;
            throw new CommandConflict("Baseline has already been reviewed.");
          }
          var w = env.worker(s, r.input().employeeId());
          if (next.equals("APPROVED")) {
            require(
                w.start().equals(r.episodeStart())
                    && Set.of("Active", "On leave").contains(w.status()),
                "employeeId",
                "Employment episode changed.");
            require(
                db.salaries(a.tenant(), w.id()).isEmpty(),
                "employeeId",
                "Another baseline was approved; reject this request.");
            var i = r.input();
            db.salary(
                a.tenant(),
                new Salary(
                    UUID.randomUUID().toString(),
                    w.id(),
                    i.currency(),
                    i.annualSalary(),
                    i.annualHours(),
                    i.effectiveDate(),
                    w.start(),
                    id,
                    1),
                s,
                c.reason(),
                corr,
                new Store.BigDecimalBonus(BigDecimal.ZERO));
          }
          var updated =
              new Setup(
                  id,
                  r.input(),
                  r.episodeStart(),
                  r.preparedBy(),
                  r.preparedEmployee(),
                  next,
                  s,
                  a.employeeId(),
                  c.reason(),
                  now());
          db.decision(a.tenant(), updated, c.idempotencyKey(), corr);
          return updated;
        });
  }

  public void apply(String s, TalentIntakeAccess.Intake intake, String reason, String corr) {
    tx.run(
        () -> {
          var a = hr(s);
          for (var award :
              intake.awards().stream()
                  .sorted(Comparator.comparing(TalentIntakeAccess.Award::employeeId))
                  .toList()) {
            db.lock(a.tenant(), award.employeeId());
            var w = env.worker(s, award.employeeId());
            var existing = db.salaries(a.tenant(), w.id());
            if (db.sourceSalary(a.tenant(), w.id(), intake.sourceId()).isPresent()) continue;
            var current =
                db.salary(a.tenant(), w.id(), award.effectiveDate())
                    .orElseThrow(
                        () ->
                            new CommandConflict(
                                "Approved effective compensation baseline is missing."));
            require(
                current.episodeStart().equals(w.start())
                    && current.currency().equals(intake.currency())
                    && current.annualSalary().compareTo(award.currentSalary()) == 0,
                "compensation",
                "Authoritative compensation or employment episode changed; return the plan and prepare a new proposal.");
            require(
                existing.stream().noneMatch(x -> x.effectiveDate().isAfter(award.effectiveDate())),
                "effectiveDate",
                "A later compensation change exists; resolve the chronological plan first.");
            require(
                !award.effectiveDate().isBefore(LocalDate.now(clock)),
                "effectiveDate",
                "Backdated changes require a dedicated payroll correction workflow.");
            require(
                !db.frozenAfter(a.tenant(), w.id(), w.start(), award.effectiveDate()),
                "effectiveDate",
                "A payroll snapshot already covers this effective date. Use an auditable payroll correction instead of changing calculated compensation.");
            CompensationCalculation.hourly(award.proposedSalary(), current.annualHours());
            db.salary(
                a.tenant(),
                new Salary(
                    UUID.randomUUID().toString(),
                    w.id(),
                    current.currency(),
                    award.proposedSalary(),
                    current.annualHours(),
                    award.effectiveDate(),
                    w.start(),
                    intake.sourceId(),
                    existing.stream().mapToLong(Salary::revision).max().orElseThrow() + 1),
                s,
                reason,
                corr,
                new Store.BigDecimalBonus(award.bonus()));
          }
          return null;
        });
  }

  public Calculation capture(
      String s,
      String runId,
      String entity,
      String currency,
      Source source,
      LocalDate postingDate,
      String reason,
      String corr) {
    return tx.run(
        () -> {
          var a = env.self(s);
          if (!env.payroll(s, entity)) throw new AccessDenied();
          db.lock(a.tenant(), source.employeeId());
          if (!a.equals(env.self(s)) || !env.payroll(s, entity)) throw new AccessDenied();
          var prior = db.calculation(a.tenant(), runId);
          if (prior.isPresent()) {
            var p = prior.get();
            if (!p.entity().equals(entity)
                || !p.source().equals(source)
                || !p.currency().equals(currency))
              throw new CommandConflict(
                  "Compensation calculation snapshot is frozen; use the original source.");
            return p;
          }
          var days = new ArrayList<Daily>();
          BigDecimal base = BigDecimal.ZERO;
          for (var d : source.days()) {
            if (d.minutes() == 0) continue;
            var salary =
                db.salary(a.tenant(), source.employeeId(), d.date())
                    .orElseThrow(
                        () ->
                            new CommandConflict(
                                "No approved salary covers worked date " + d.date() + "."));
            require(
                salary.currency().equals(currency)
                    && salary.episodeStart().equals(source.episodeStart()),
                "compensation",
                "Salary currency or employment episode does not match approved time.");
            var rate = CompensationCalculation.hourly(salary.annualSalary(), salary.annualHours());
            days.add(new Daily(d.date(), d.minutes(), salary, rate));
            base = base.add(CompensationCalculation.earned(d.minutes(), rate));
          }
          base = CompensationCalculation.money(base);
          var bonuses =
              db.bonuses(
                  a.tenant(),
                  source.employeeId(),
                  source.episodeStart(),
                  source.week().plusDays(6).isBefore(postingDate)
                      ? source.week().plusDays(6)
                      : postingDate);
          var bonus = bonuses.stream().map(Bonus::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
          var rate =
              base.multiply(BigDecimal.valueOf(60))
                  .divide(BigDecimal.valueOf(source.totalMinutes()), 4, RoundingMode.HALF_EVEN);
          require(
              base.add(bonus).compareTo(new BigDecimal("999999999999.99")) <= 0,
              "gross",
              "Gross exceeds the supported exact monetary limit.");
          var r =
              new Calculation(
                  runId,
                  source.employeeId(),
                  entity,
                  currency,
                  source,
                  List.copyOf(days),
                  bonuses,
                  rate,
                  base,
                  CompensationCalculation.money(bonus),
                  base.add(bonus),
                  CompensationCalculation.RULE,
                  s,
                  reason,
                  now(),
                  corr);
          db.capture(a.tenant(), r);
          return r;
        });
  }

  public Calculation calculation(String s, String runId) {
    return tx.run(
        () -> {
          var a = env.self(s);
          var r = db.calculation(a.tenant(), runId).orElseThrow(AccessDenied::new);
          if (r != null
              && !env.payroll(s, r.entity())
              && !db.released(a.tenant(), runId, a.employeeId())) throw new AccessDenied();
          return r;
        });
  }
}
