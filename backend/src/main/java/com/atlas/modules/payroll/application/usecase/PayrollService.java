package com.atlas.modules.payroll.application.usecase;

import com.atlas.modules.payroll.application.model.PayrollModels.*;
import com.atlas.modules.payroll.application.port.in.PayrollUseCases;
import com.atlas.modules.payroll.application.port.out.PayrollPorts.*;
import com.atlas.modules.payroll.domain.model.PayrollCalculation;
import com.atlas.modules.payroll.domain.model.PayrollWorkflow;
import com.atlas.shared.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;

public final class PayrollService implements PayrollUseCases {
  private final Store store;
  private final Environment env;
  private final Transactions reads, writes;
  private final Clock clock;

  public PayrollService(
      Store store, Environment env, Transactions reads, Transactions writes, Clock clock) {
    this.store = store;
    this.env = env;
    this.reads = reads;
    this.writes = writes;
    this.clock = clock;
  }

  private Instant now() {
    return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  private void check(boolean b, String f, String m) {
    if (!b) throw new InputValidation(Map.of(f, m));
  }

  private String text(String v, String f, int max) {
    check(
        v != null
            && !v.isBlank()
            && v.length() <= max
            && v.codePoints().noneMatch(Character::isISOControl),
        f,
        "Enter 1–" + max + " plain text characters.");
    return v.strip();
  }

  private String key(String k) {
    check(
        k != null && k.matches("[a-zA-Z0-9-]{8,80}"),
        "idempotencyKey",
        "Supply a valid command key.");
    return k;
  }

  private long version(BigDecimal v) {
    try {
      check(v != null && v.signum() >= 0, "version", "Supply an exact nonnegative revision.");
      return v.longValueExact();
    } catch (ArithmeticException e) {
      throw new InputValidation(Map.of("version", "Supply an exact nonnegative revision."));
    }
  }

  private boolean allowed(String subject, String entity, String permission) {
    return env.grants(subject).stream()
        .anyMatch(g -> g.entity().equals(entity) && g.permission().equals(permission));
  }

  private Identity access(String subject, String entity, String permission) {
    var a = env.self(subject);
    if (!allowed(subject, entity, permission)) throw new AccessDenied();
    return a;
  }

  private Identity locked(String subject, String entity, String permission) {
    var a = access(subject, entity, permission);
    store.lock(a.tenant(), entity);
    var b = access(subject, entity, permission);
    if (!a.equals(b)) throw new CommandConflict("Identity changed; reload.");
    return b;
  }

  private String fp(Object c) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(c.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Run replay(Identity a, String key, String fingerprint) {
    store.lock(a.tenant(), "command/" + a.subject() + "/" + key(key));
    var p = store.receipt(a.tenant(), a.subject(), key);
    if (p.isEmpty()) return null;
    if (!p.get().fingerprint().equals(fingerprint))
      throw new CommandConflict("Command key already used for different data.");
    return store.decode(p.get().result());
  }

  public List<Grant> grants(String subject) {
    return reads.run(
        () -> {
          env.self(subject);
          return env.grants(subject).stream()
              .filter(g -> g.permission().startsWith("PAYROLL_"))
              .toList();
        });
  }

  public Page runs(String subject, String entity, int page) {
    check(page >= 0 && page <= 10000, "page", "Use page 0–10000.");
    return reads.run(
        () -> {
          var a = env.self(subject);
          var all =
              allowed(subject, entity, "PAYROLL_PREPARE")
                  || allowed(subject, entity, "PAYROLL_APPROVE");
          return store.runs(a.tenant(), entity, a.employeeId(), all, page);
        });
  }

  public Run run(String subject, String id) {
    return reads.run(
        () -> {
          var a = env.self(subject);
          var r = store.run(a.tenant(), id).orElseThrow(AccessDenied::new);
          if (!(allowed(subject, r.entity(), "PAYROLL_PREPARE")
              || allowed(subject, r.entity(), "PAYROLL_APPROVE")
              || (r.employeeId().equals(a.employeeId()) && r.status().equals("FINALIZED"))))
            throw new AccessDenied();
          return r;
        });
  }

  public Run prepare(String subject, Prepare c, String correlation) {
    return writes.run(
        () -> {
          var a = locked(subject, c.entity(), "PAYROLL_PREPARE");
          var reason = text(c.reason(), "reason", 500);
          var v = version(c.version());
          var fingerprint = fp(c);
          var replay = replay(a, c.idempotencyKey(), fingerprint);
          if (replay != null) return replay;
          var ledger = env.ledger(subject, c.entity());
          check(
              ledger.currency().equals("USD"),
              "currency",
              "Only demonstration USD calculations are implemented.");
          check(
              ledger.costCentres().contains(c.costCentre()),
              "costCentre",
              "Choose a configured cost centre.");
          var source = env.source(subject, text(c.sheetId(), "sheetId", 40));
          var worker = env.worker(subject, source.employeeId());
          check(
              Set.of("Active", "On leave").contains(worker.status())
                  && worker.start().equals(source.episodeStart())
                  && c.entity().equals(worker.entityCode()),
              "employee",
              "An active current employment episode assigned to this legal entity is required.");
          check(
              c.postingDate() != null
                  && !c.postingDate().isBefore(ledger.effectiveFrom())
                  && !c.postingDate().isAfter(LocalDate.now(clock)),
              "postingDate",
              "Choose an effective, non-future posting date.");
          check(
              source.days().stream()
                  .filter(d -> d.minutes() > 0)
                  .allMatch(
                      d ->
                          !d.date().isBefore(ledger.effectiveFrom())
                              && !d.date().isAfter(c.postingDate())),
              "source",
              "Worked dates must belong to the effective entity and not follow posting date.");
          var calculation =
              PayrollCalculation.calculate(source.totalMinutes(), c.hourlyRate(), c.deduction());
          Run old = null;
          if (c.id() != null && !c.id().isBlank()) {
            old = store.run(a.tenant(), c.id()).orElseThrow(AccessDenied::new);
            if (!old.entity().equals(c.entity()) || !old.preparedEmployee().equals(a.employeeId()))
              throw new AccessDenied();
            if (!Set.of("CALCULATED", "RETURNED").contains(old.status()) || old.version() != v)
              throw new CommandConflict(
                  "Only current calculated or returned runs may be recalculated.");
            check(
                old.source().id().equals(source.id()),
                "source",
                "A recalculation must retain its original source.");
          } else {
            check(v == 0, "version", "New runs start at revision 0.");
            store.lock(a.tenant(), "source/" + source.id());
            if (store.source(a.tenant(), source.id()).isPresent())
              throw new CommandConflict(
                  "Approved time already belongs to a payroll run; use that run's controlled correction workflow.");
          }
          var r =
              new Run(
                  old == null ? UUID.randomUUID().toString() : old.id(),
                  c.entity(),
                  source.employeeId(),
                  source.episodeStart(),
                  source,
                  ledger.currency(),
                  c.costCentre(),
                  c.postingDate(),
                  c.hourlyRate(),
                  calculation.gross(),
                  calculation.deduction(),
                  calculation.net(),
                  PayrollCalculation.RULE,
                  calculation.exceptions(),
                  "CALCULATED",
                  old == null ? 0 : v + 1,
                  old == null ? a.subject() : old.preparedBy(),
                  old == null ? a.employeeId() : old.preparedEmployee(),
                  null,
                  null,
                  null,
                  "NOT_CONFIGURED",
                  reason,
                  now());
          persist(a, old, r, "CALCULATED", reason, c.idempotencyKey(), fingerprint, correlation);
          return r;
        });
  }

  private void persist(
      Identity a,
      Run old,
      Run r,
      String action,
      String reason,
      String key,
      String fp,
      String correlation) {
    store.save(a.tenant(), r);
    store.audit(a, r.id(), action, reason, old, r, now(), correlation);
    store.receipt(a, key, fp, r);
  }

  public Run action(String subject, String id, Action c, String correlation) {
    return writes.run(
        () -> {
          var initial = run(subject, id);
          var action = text(c.action(), "action", 30);
          check(
              Set.of("SUBMIT", "FINALIZE", "RETURN", "CREATE_JOURNAL").contains(action),
              "action",
              "Choose SUBMIT, FINALIZE, RETURN or CREATE_JOURNAL.");
          var a =
              locked(
                  subject,
                  initial.entity(),
                  Set.of("FINALIZE", "RETURN").contains(action)
                      ? "PAYROLL_APPROVE"
                      : "PAYROLL_PREPARE");
          var old = store.run(a.tenant(), id).orElseThrow(AccessDenied::new);
          var v = version(c.version());
          var reason = text(c.reason(), "reason", 500);
          var fingerprint = fp(List.of(id, action, v, reason));
          var replay = replay(a, c.idempotencyKey(), fingerprint);
          if (replay != null) return replay;
          if (old.version() != v) throw new CommandConflict("Payroll changed; reload.");
          if (action.equals("SUBMIT") && !old.preparedEmployee().equals(a.employeeId()))
            throw new AccessDenied();
          if (Set.of("FINALIZE", "RETURN").contains(action)
              && (old.preparedEmployee().equals(a.employeeId())
                  || old.employeeId().equals(a.employeeId())
                  || old.preparedBy().equals(subject))) throw new AccessDenied();
          var next =
              PayrollWorkflow.transition(
                  old.status(), action, !old.exceptions().isEmpty(), old.journalId() != null);
          var journal = old.journalId();
          if (action.equals("CREATE_JOURNAL"))
            journal = env.journal(subject, old, reason, c.idempotencyKey(), correlation);
          var r =
              new Run(
                  old.id(),
                  old.entity(),
                  old.employeeId(),
                  old.episodeStart(),
                  old.source(),
                  old.currency(),
                  old.costCentre(),
                  old.postingDate(),
                  old.hourlyRate(),
                  old.gross(),
                  old.deduction(),
                  old.net(),
                  old.ruleVersion(),
                  old.exceptions(),
                  next,
                  v + 1,
                  old.preparedBy(),
                  old.preparedEmployee(),
                  action.equals("FINALIZE") ? subject : old.approvedBy(),
                  action.equals("FINALIZE") ? a.employeeId() : old.approvedEmployee(),
                  journal,
                  old.paymentStatus(),
                  reason,
                  now());
          persist(a, old, r, action, reason, c.idempotencyKey(), fingerprint, correlation);
          return r;
        });
  }

  public List<Audit> history(String subject, String id) {
    return reads.run(
        () -> {
          var r = run(subject, id);
          return store.history(env.self(subject).tenant(), r.id());
        });
  }

  public Document payslip(String subject, String id, String correlation) {
    return writes.run(
        () -> {
          var r = run(subject, id);
          if (!r.status().equals("FINALIZED"))
            throw new CommandConflict("Only finalized payslips are released.");
          var a = env.self(subject);
          store.audit(
              a,
              id,
              "PAYSLIP_READ",
              "Released demonstration payslip retrieved",
              null,
              Map.of("runId", id),
              now(),
              correlation);
          var text =
              "DEMONSTRATION PAYSLIP — NOT STATUTORY OR EVIDENCE OF PAYMENT\nRun "
                  + id
                  + "\nWorker "
                  + r.employeeId()
                  + "\nWeek "
                  + r.source().week()
                  + "\nCurrency "
                  + r.currency()
                  + "\nApproved minutes "
                  + r.source().totalMinutes()
                  + "\nEntered hourly rate "
                  + r.hourlyRate().toPlainString()
                  + "\nGross "
                  + r.gross().toPlainString()
                  + "\nVoluntary deduction "
                  + r.deduction().toPlainString()
                  + "\nNet "
                  + r.net().toPlainString()
                  + "\nTaxes and statutory contributions: NOT CONFIGURED\nPayment: "
                  + r.paymentStatus()
                  + "\nRule "
                  + r.ruleVersion()
                  + "\n";
          return new Document("demo-payslip-" + id + ".txt", text);
        });
  }
}
