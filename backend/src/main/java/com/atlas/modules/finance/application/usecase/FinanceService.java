package com.atlas.modules.finance.application.usecase;

import static com.atlas.modules.finance.domain.model.AccountingPolicy.*;

import com.atlas.modules.finance.application.model.FinanceModels.*;
import com.atlas.modules.finance.application.model.FinanceModels.Period;
import com.atlas.modules.finance.application.port.in.FinanceUseCases;
import com.atlas.modules.finance.application.port.out.FinancePorts.*;
import com.atlas.modules.finance.domain.model.JournalWorkflow;
import com.atlas.shared.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;

public final class FinanceService implements FinanceUseCases {
  private final Store store;
  private final References refs;
  private final Transactions reads, writes;
  private final Clock clock;

  public FinanceService(Store s, References r, Transactions reads, Transactions writes, Clock c) {
    this.store = s;
    this.refs = r;
    this.reads = reads;
    this.writes = writes;
    this.clock = c;
  }

  private Instant now() {
    return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  private Identity access(String subject, String entity, String permission) {
    var a = refs.self(subject);
    if (store.grants(a).stream()
        .noneMatch(g -> g.entity().equals(entity) && g.permission().equals(permission)))
      throw new AccessDenied();
    return a;
  }

  private Identity locked(String subject, String entity, String permission) {
    var a = access(subject, entity, permission);
    store.lock(a.tenant(), entity);
    var b = access(subject, entity, permission);
    if (!a.equals(b)) throw new CommandConflict("Identity changed; reload.");
    return b;
  }

  private String digest(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T replay(Identity a, String key, String fp, Class<T> type) {
    store.lock(a.tenant(), "command/" + a.subject() + "/" + key(key));
    var p = store.receipt(a.tenant(), a.subject(), key);
    if (p.isEmpty()) return null;
    if (!p.get().fingerprint().equals(fp))
      throw new CommandConflict("Command key already used for different data.");
    return store.decode(p.get().result(), type);
  }

  private Ledger ledger(Identity a, String entity) {
    return store
        .ledger(a.tenant(), entity)
        .orElseThrow(() -> new CommandConflict("Configure the entity ledger first."));
  }

  private void open(Identity a, Ledger l, LocalDate date) {
    require(
        date != null && !date.isBefore(l.effectiveFrom()) && !date.isAfter(LocalDate.now(clock)),
        "postingDate",
        "Choose an effective, non-future posting date.");
    var ref = refs.entity(a.subject(), l.entity());
    require(
        !date.isBefore(ref.effectiveFrom())
            && (ref.retiredOn() == null || date.isBefore(ref.retiredOn())),
        "entity",
        "Entity is not effective on the posting date.");
    if (store
        .period(a.tenant(), l.entity(), date.withDayOfMonth(1))
        .map(Period::closed)
        .orElse(false)) throw new CommandConflict("Posting period is closed.");
  }

  private List<Line> lines(Ledger l, List<Line> inputs) {
    require(
        inputs != null && inputs.size() >= 2 && inputs.size() <= 100,
        "lines",
        "Enter 2–100 balanced lines.");
    var out = new ArrayList<Line>();
    for (var e : inputs) {
      require(e != null, "lines", "Enter valid lines.");
      var account =
          l.accounts().stream()
              .filter(x -> x.code().equals(e.account()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new InputValidation(
                          Map.of("account", "Account is not configured for this entity.")));
      var cc = e.costCentre() == null ? "" : e.costCentre().strip();
      require(
          (cc.isEmpty() && !account.requiresCostCentre()) || l.costCentres().contains(cc),
          "costCentre",
          "Choose a configured cost centre; expense accounts require one.");
      var d = money(e.debit(), "debit");
      var c = money(e.credit(), "credit");
      require(
          (d.signum() > 0) != (c.signum() > 0),
          "lines",
          "Each line must contain exactly one positive debit or credit.");
      out.add(new Line(account.code(), cc, d, c, text(e.memo(), "memo", 200)));
    }
    balanced(out.stream().map(Line::debit).toList(), out.stream().map(Line::credit).toList());
    return List.copyOf(out);
  }

  public Ledger payrollLedger(String subject, String entity) {
    return reads.run(
        () -> {
          var a = refs.self(subject);
          if (store.grants(a).stream()
              .noneMatch(
                  g ->
                      g.entity().equals(entity)
                          && Set.of("PAYROLL_PREPARE", "PAYROLL_APPROVE").contains(g.permission())))
            throw new AccessDenied();
          return ledger(a, entity);
        });
  }

  public Context context(String subject) {
    return reads.run(
        () -> {
          var a = refs.self(subject);
          var grants = store.grants(a);
          return new Context(
              grants,
              grants.stream()
                  .map(Grant::entity)
                  .distinct()
                  .map(e -> store.ledger(a.tenant(), e).orElse(null))
                  .filter(Objects::nonNull)
                  .toList());
        });
  }

  public Ledger setup(String subject, Setup c, String correlation) {
    return writes.run(
        () -> {
          var entity = code(c.entity());
          var a = locked(subject, entity, "SETUP");
          var reason = text(c.reason(), "reason", 500);
          var fp = digest(List.of("SETUP", entity, String.valueOf(c.costCentres()), reason));
          var replay = replay(a, c.idempotencyKey(), fp, Ledger.class);
          if (replay != null) return replay;
          if (store.ledger(a.tenant(), entity).isPresent())
            throw new CommandConflict(
                "Ledger already configured; amendments require a later controlled configuration workflow.");
          var ref = refs.entity(subject, entity);
          require(
              ref.currency().equals("USD"),
              "currency",
              "This explicitly demonstration-only ledger supports USD with two-decimal precision.");
          require(
              c.costCentres() != null && !c.costCentres().isEmpty() && c.costCentres().size() <= 50,
              "costCentres",
              "Configure 1–50 cost centres.");
          var centres = c.costCentres().stream().map(x -> code(x)).distinct().toList();
          require(
              centres.size() == c.costCentres().size(),
              "costCentres",
              "Cost centres must be unique.");
          var l =
              new Ledger(
                  entity,
                  ref.currency(),
                  ref.effectiveFrom(),
                  centres,
                  List.of(
                      new Account("6000", "Demonstration wage expense", "EXPENSE", true),
                      new Account("2100", "Voluntary deductions payable", "LIABILITY", false),
                      new Account("2200", "Net pay payable", "LIABILITY", false),
                      new Account("1000", "Demonstration bank", "ASSET", false)));
          store.insertLedger(a.tenant(), l);
          store.audit(a, entity, entity, "LEDGER_CONFIGURED", reason, null, l, now(), correlation);
          store.receipt(a.tenant(), subject, c.idempotencyKey(), fp, l);
          return l;
        });
  }

  public Page<Journal> journals(String subject, String entity, String status, int page) {
    require(page >= 0 && page <= 10000, "page", "Use page 0–10000.");
    require(
        Set.of(
                "ALL",
                "DRAFT",
                "SUBMITTED",
                "APPROVED",
                "POSTED",
                "REVERSED",
                "RETURNED",
                "REJECTED")
            .contains(status),
        "status",
        "Choose a valid journal status.");
    return reads.run(
        () -> {
          var a = access(subject, entity, "READ");
          return store.journals(a.tenant(), entity, status, page);
        });
  }

  public Journal journal(String subject, String id) {
    return reads.run(
        () -> {
          var a = refs.self(subject);
          var j = store.journal(a.tenant(), id).orElseThrow(AccessDenied::new);
          access(subject, j.entity(), "READ");
          return j;
        });
  }

  public Journal save(String subject, SaveJournal c, String correlation) {
    return writes.run(
        () -> {
          var a = locked(subject, c.entity(), "PREPARE");
          var l = ledger(a, c.entity());
          var reason = text(c.reason(), "reason", 500);
          var version = version(c.version());
          var items = lines(l, c.lines());
          var fp =
              digest(
                  List.of(
                      "SAVE",
                      String.valueOf(c.id()),
                      c.entity(),
                      String.valueOf(c.postingDate()),
                      version,
                      items,
                      reason));
          var replay = replay(a, c.idempotencyKey(), fp, Journal.class);
          if (replay != null) return replay;
          open(a, l, c.postingDate());
          Journal old = null;
          if (c.id() != null && !c.id().isBlank()) {
            old = store.journal(a.tenant(), c.id()).orElseThrow(AccessDenied::new);
            if (!old.entity().equals(c.entity()) || !old.preparedEmployee().equals(a.employeeId()))
              throw new AccessDenied();
            if (!Set.of("DRAFT", "RETURNED", "REJECTED").contains(old.status())
                || !old.sourceType().equals("MANUAL"))
              throw new CommandConflict("This journal cannot be edited.");
            if (old.version() != version) throw new CommandConflict("Journal changed; reload.");
          } else require(version == 0, "version", "New journals start at revision 0.");
          var j =
              new Journal(
                  old == null ? UUID.randomUUID().toString() : old.id(),
                  c.entity(),
                  l.currency(),
                  c.postingDate(),
                  "MANUAL",
                  old == null ? UUID.randomUUID().toString() : old.sourceId(),
                  null,
                  "DRAFT",
                  old == null ? 0 : version + 1,
                  old == null ? a.subject() : old.preparedBy(),
                  old == null ? a.employeeId() : old.preparedEmployee(),
                  null,
                  null,
                  items,
                  reason,
                  now());
          persist(a, old, j, "SAVED", reason, c.idempotencyKey(), fp, correlation);
          return j;
        });
  }

  private void persist(
      Identity a,
      Journal before,
      Journal after,
      String action,
      String reason,
      String key,
      String fp,
      String correlation) {
    store.save(a.tenant(), after);
    store.audit(a, after.entity(), after.id(), action, reason, before, after, now(), correlation);
    store.receipt(a.tenant(), a.subject(), key, fp, after);
  }

  public Journal preparePayroll(String subject, PayrollCommand c, String correlation) {
    return writes.run(
        () -> {
          var a = locked(subject, c.entity(), "PREPARE");
          var l = ledger(a, c.entity());
          require(
              l.currency().equals(c.currency()),
              "currency",
              "Payroll and ledger currencies must match.");
          var reason = text(c.reason(), "reason", 500);
          var gross = money(c.gross(), "gross");
          var deduction = money(c.deduction(), "deduction");
          var net = money(c.net(), "net");
          require(
              gross.compareTo(deduction.add(net)) == 0 && net.signum() > 0,
              "totals",
              "Gross must equal deductions plus positive net pay.");
          var fp = digest(c);
          var replay = replay(a, c.idempotencyKey(), fp, Journal.class);
          if (replay != null) return replay;
          open(a, l, c.postingDate());
          var existing = store.source(a.tenant(), c.entity(), "PAYROLL", c.runId());
          if (existing.isPresent())
            throw new CommandConflict("Payroll already has an accounting document.");
          var list = new ArrayList<Line>();
          list.add(
              new Line("6000", c.costCentre(), gross, BigDecimal.ZERO, "Payroll " + c.runId()));
          if (deduction.signum() > 0)
            list.add(
                new Line("2100", "", BigDecimal.ZERO, deduction, "Payroll deduction liability"));
          list.add(new Line("2200", "", BigDecimal.ZERO, net, "Payroll net liability"));
          var j =
              new Journal(
                  UUID.randomUUID().toString(),
                  c.entity(),
                  l.currency(),
                  c.postingDate(),
                  "PAYROLL",
                  c.runId(),
                  null,
                  "DRAFT",
                  0,
                  a.subject(),
                  a.employeeId(),
                  null,
                  null,
                  lines(l, list),
                  reason,
                  now());
          persist(a, null, j, "PAYROLL_DRAFT", reason, c.idempotencyKey(), fp, correlation);
          return j;
        });
  }

  public Journal action(String subject, String id, Action c, String correlation) {
    return writes.run(
        () -> {
          var initial = journal(subject, id);
          var action = text(c.action(), "action", 20);
          require(
              Set.of("SUBMIT", "APPROVE", "RETURN", "REJECT", "POST", "REVERSE").contains(action),
              "action",
              "Choose a valid journal action.");
          var permission =
              Set.of("APPROVE", "RETURN", "REJECT").contains(action)
                  ? "APPROVE"
                  : action.equals("POST") ? "POST" : "PREPARE";
          var a = locked(subject, initial.entity(), permission);
          var old = store.journal(a.tenant(), id).orElseThrow(AccessDenied::new);
          var reason = text(c.reason(), "reason", 500);
          var v = version(c.version());
          var fp = digest(List.of(id, action, v, reason));
          var replay = replay(a, c.idempotencyKey(), fp, Journal.class);
          if (replay != null) return replay;
          if (old.version() != v) throw new CommandConflict("Journal changed; reload.");
          var l = ledger(a, old.entity());
          if (action.equals("SUBMIT") && !old.preparedEmployee().equals(a.employeeId()))
            throw new AccessDenied();
          if (Set.of("APPROVE", "RETURN", "REJECT").contains(action)
              && (old.preparedEmployee().equals(a.employeeId())
                  || old.preparedBy().equals(subject))) throw new AccessDenied();
          if (action.equals("POST")
              && (old.preparedEmployee().equals(a.employeeId())
                  || a.employeeId().equals(old.approvedEmployee())
                  || old.preparedBy().equals(subject)
                  || subject.equals(old.approvedBy()))) throw new AccessDenied();
          var next = JournalWorkflow.transition(old.status(), action, old.sourceType());
          if (action.equals("REVERSE")) {
            if (store.source(a.tenant(), old.entity(), "REVERSAL", old.id()).isPresent())
              throw new CommandConflict("A reversal already exists.");
            open(a, l, LocalDate.now(clock));
            var reversed =
                old.lines().stream()
                    .map(
                        x ->
                            new Line(
                                x.account(),
                                x.costCentre(),
                                x.credit(),
                                x.debit(),
                                "Reversal of " + id))
                    .toList();
            var j =
                new Journal(
                    UUID.randomUUID().toString(),
                    old.entity(),
                    old.currency(),
                    LocalDate.now(clock),
                    "REVERSAL",
                    id,
                    id,
                    "DRAFT",
                    0,
                    a.subject(),
                    a.employeeId(),
                    null,
                    null,
                    reversed,
                    reason,
                    now());
            persist(a, null, j, "REVERSAL_PREPARED", reason, c.idempotencyKey(), fp, correlation);
            return j;
          }
          if (Set.of("SUBMITTED", "APPROVED", "POSTED").contains(next))
            open(a, l, old.postingDate());
          var j =
              new Journal(
                  id,
                  old.entity(),
                  old.currency(),
                  old.postingDate(),
                  old.sourceType(),
                  old.sourceId(),
                  old.reversalOf(),
                  next,
                  v + 1,
                  old.preparedBy(),
                  old.preparedEmployee(),
                  next.equals("APPROVED") ? subject : old.approvedBy(),
                  next.equals("APPROVED") ? a.employeeId() : old.approvedEmployee(),
                  old.lines(),
                  reason,
                  now());
          if (next.equals("POSTED") && old.reversalOf() != null) {
            var original =
                store.journal(a.tenant(), old.reversalOf()).orElseThrow(AccessDenied::new);
            var reversedStatus = JournalWorkflow.counterEntryPosted(original.status());
            var changed =
                new Journal(
                    original.id(),
                    original.entity(),
                    original.currency(),
                    original.postingDate(),
                    original.sourceType(),
                    original.sourceId(),
                    original.reversalOf(),
                    reversedStatus,
                    original.version() + 1,
                    original.preparedBy(),
                    original.preparedEmployee(),
                    original.approvedBy(),
                    original.approvedEmployee(),
                    original.lines(),
                    reason,
                    now());
            store.save(a.tenant(), changed);
            store.audit(
                a,
                changed.entity(),
                changed.id(),
                "REVERSED",
                reason,
                original,
                changed,
                now(),
                correlation);
          }
          persist(a, old, j, action, reason, c.idempotencyKey(), fp, correlation);
          return j;
        });
  }

  public Period period(String subject, String entity, LocalDate month) {
    return reads.run(
        () -> {
          var a = access(subject, entity, "READ");
          require(
              month != null && month.getDayOfMonth() == 1,
              "month",
              "Use the first day of the fiscal month.");
          return store.period(a.tenant(), entity, month).orElse(null);
        });
  }

  public Period setPeriod(String subject, SetPeriod c, String correlation) {
    return writes.run(
        () -> {
          var a = locked(subject, c.entity(), "SETUP");
          ledger(a, c.entity());
          require(
              c.month() != null && c.month().getDayOfMonth() == 1 && c.closed() != null,
              "month",
              "Provide a month-start date and explicit closed flag.");
          var reason = text(c.reason(), "reason", 500);
          var v = version(c.version());
          var fp = digest(List.of(c.entity(), c.month(), c.closed(), v, reason));
          var replay = replay(a, c.idempotencyKey(), fp, Period.class);
          if (replay != null) return replay;
          var before = store.period(a.tenant(), c.entity(), c.month()).orElse(null);
          if ((before == null ? 0 : before.version()) != v)
            throw new CommandConflict("Period changed; reload.");
          var p = new Period(c.month(), c.closed(), v + 1, reason, subject, now());
          store.insertPeriod(a.tenant(), c.entity(), p);
          store.audit(
              a,
              c.entity(),
              c.month().toString(),
              c.closed() ? "PERIOD_CLOSED" : "PERIOD_REOPENED",
              reason,
              before,
              p,
              now(),
              correlation);
          store.receipt(a.tenant(), subject, c.idempotencyKey(), fp, p);
          return p;
        });
  }

  public TrialBalance trialBalance(
      String subject, String entity, LocalDate from, LocalDate through) {
    return reads.run(
        () -> {
          var a = access(subject, entity, "READ");
          var l = ledger(a, entity);
          require(
              from != null
                  && through != null
                  && !from.isAfter(through)
                  && java.time.temporal.ChronoUnit.DAYS.between(from, through) <= 366,
              "dates",
              "Choose an ordered range of at most 367 days.");
          var rows = store.balances(a.tenant(), entity, from, through);
          return new TrialBalance(
              entity,
              l.currency(),
              from,
              through,
              rows,
              rows.stream().map(Balance::debit).reduce(BigDecimal.ZERO, BigDecimal::add),
              rows.stream().map(Balance::credit).reduce(BigDecimal.ZERO, BigDecimal::add));
        });
  }

  public Page<Audit> history(String subject, String entity, int page) {
    require(page >= 0 && page <= 10000, "page", "Use page 0–10000.");
    return reads.run(
        () -> {
          var a = access(subject, entity, "READ");
          return store.history(a.tenant(), entity, page);
        });
  }
}
