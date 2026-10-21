package com.atlas.modules.talent.application.usecase;

import static com.atlas.modules.talent.domain.model.TalentPolicy.*;

import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.application.port.out.TalentPorts.*;
import com.atlas.modules.talent.domain.model.TalentModels.*;
import com.atlas.shared.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

abstract class TalentSupport {
  protected final Store db;
  protected final Environment env;
  protected final Transactions reads, writes;
  protected final Clock clock;

  protected TalentSupport(Store s, Environment e, Transactions r, Transactions w, Clock c) {
    db = s;
    env = e;
    reads = r;
    writes = w;
    clock = c;
  }

  protected Instant now() {
    return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  protected LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  protected String id() {
    return UUID.randomUUID().toString();
  }

  protected void hr(Actor a) {
    if (!a.role().equals("HR")) throw new AccessDenied();
  }

  protected void manager(Actor a, String employee) {
    var w = env.worker(a.subject(), employee);
    if (!a.role().equals("HR")
        && !(a.role().equals("MANAGER")
            && w.managerId().equals(a.employeeId())
            && !employee.equals(a.employeeId()))) throw new AccessDenied();
  }

  protected Worker eligible(Actor a, String employee) {
    var w = env.worker(a.subject(), employee);
    require(
        Set.of("Active", "Onboarding", "On leave").contains(w.status()),
        "employeeId",
        "Select a current worker.");
    return w;
  }

  protected long version(BigDecimal v) {
    try {
      require(v != null && v.signum() >= 0, "version", "Supply an exact nonnegative revision.");
      return v.longValueExact();
    } catch (ArithmeticException e) {
      throw new InputValidation(Map.of("version", "Supply an exact nonnegative revision."));
    }
  }

  protected void revision(long current, BigDecimal expected) {
    if (current != version(expected))
      throw new CommandConflict("Another user changed this record. Reload the current revision.");
  }

  protected String currency(String c) {
    require(
        c != null && Set.of("USD", "EUR", "GBP", "INR").contains(c),
        "currency",
        "Select USD, EUR, GBP or INR. No currency conversion is performed.");
    return c;
  }

  protected Stamp stamp(Actor a, String state) {
    return new Stamp(id(), 0, state, a.subject(), a.employeeId(), now());
  }

  protected Stamp meta(Object o) {
    return switch (o) {
      case Requisition r -> r.meta();
      case Onboarding r -> r.meta();
      case Review r -> r.meta();
      case Course r -> r.meta();
      case Enrollment r -> r.meta();
      case Compensation r -> r.meta();
      case Succession r -> r.meta();
      case Scenario r -> r.meta();
      default -> throw new IllegalArgumentException("Unknown talent record.");
    };
  }

  protected Class<?> type(String k) {
    return switch (k) {
      case "requisitions" -> Requisition.class;
      case "onboarding" -> Onboarding.class;
      case "reviews" -> Review.class;
      case "courses" -> Course.class;
      case "learning" -> Enrollment.class;
      case "compensation" -> Compensation.class;
      case "succession" -> Succession.class;
      case "scenarios" -> Scenario.class;
      default -> throw new InputValidation(Map.of("kind", "Select a talent capability."));
    };
  }

  protected void readAccess(Actor a, String k, Object r) {
    switch (k) {
      case "requisitions", "compensation", "succession", "scenarios" -> hr(a);
      case "reviews" -> {
        var v = (Review) r;
        env.worker(a.subject(), v.employeeId());
      }
      case "learning" -> {
        var v = (Enrollment) r;
        env.worker(a.subject(), v.employeeId());
      }
      case "onboarding" -> {
        var v = (Onboarding) r;
        if (!a.role().equals("HR") && !v.employeeId().equals(a.employeeId()))
          throw new AccessDenied();
      }
      case "courses" -> {}
      default -> type(k);
    }
  }

  protected <T> T load(Actor a, String k, String id, Class<T> type) {
    var r = db.find(a.tenant(), k, id, type).orElseThrow(AccessDenied::new);
    readAccess(a, k, r);
    return r;
  }

  public Context context(String s, String search, int page) {
    require(
        search != null && search.length() <= 100 && page >= 0 && page <= 10000,
        "page",
        "Use a bounded search and page.");
    return reads.run(() -> new Context(env.self(s), env.workers(s, search, page)));
  }

  public Object page(String s, String k, String employee, int page) {
    require(page >= 0 && page <= 10000, "page", "Use page 0–10000.");
    return reads.run(
        () -> {
          var a = env.self(s);
          type(k);
          String scope = "";
          if (Set.of("reviews", "learning", "onboarding").contains(k)) {
            scope = employee == null || employee.isBlank() ? a.employeeId() : employee;
            if (!scope.equals(a.employeeId())) {
              if (k.equals("onboarding")) hr(a);
              else env.worker(s, scope);
            }
            if (a.role().equals("HR") && (employee == null || employee.isBlank())) scope = "";
          } else if (!k.equals("courses")) hr(a);
          return db.page(a.tenant(), k, scope, page, type(k));
        });
  }

  public Object record(String s, String k, String id) {
    return reads.run(() -> load(env.self(s), k, id, type(k)));
  }

  public List<Audit> history(String s, String k, String id, int page) {
    require(page >= 0 && page <= 10000, "page", "Use a bounded history page.");
    return reads.run(
        () -> {
          var a = env.self(s);
          load(a, k, id, type(k));
          return db.history(a.tenant(), k, id, page);
        });
  }

  public List<Notice> notices(String s, int page) {
    require(page >= 0 && page <= 10000, "page", "Use a bounded notification page.");
    return reads.run(
        () -> {
          var a = env.self(s);
          var visible = new ArrayList<Notice>();
          for (var n : db.notices(a.tenant(), a.employeeId(), page)) {
            try {
              load(a, n.kind(), n.recordId(), type(n.kind()));
              visible.add(n);
            } catch (AccessDenied denied) {
              /* Revoked record access removes private context from inbox. */
            }
          }
          return List.copyOf(visible);
        });
  }

  private String csv(String value) {
    if (value == null) value = "";
    if (!value.isEmpty() && "=+-@\t\r\n".indexOf(value.charAt(0)) >= 0) value = "'" + value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  public Export export(String s, String kind, String employee, int page, String corr) {
    return writes.run(
        () -> {
          var a = env.self(s);
          var records = (Page<?>) page(s, kind, employee, page);
          var out = new StringBuilder("id,status,revision,authorized_snapshot\r\n");
          for (var r : records.items()) {
            readAccess(a, kind, r);
            var m = meta(r);
            out.append(csv(m.id()))
                .append(',')
                .append(csv(m.status()))
                .append(',')
                .append(m.version())
                .append(',')
                .append(csv(db.encode(r)))
                .append("\r\n");
          }
          db.exportReceipt(
              a.tenant(),
              a.subject(),
              a.employeeId(),
              kind,
              page,
              records.items().size(),
              corr,
              now());
          return new Export("talent-" + kind + "-page-" + (page + 1) + ".csv", out.toString());
        });
  }

  protected String fingerprint(Object c) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(db.encode(c).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  protected <T> T command(
      String s,
      String k,
      String recordId,
      Object input,
      String reason,
      String key,
      String correlation,
      Class<T> type,
      Function<Actor, T> work) {
    text(reason, "reason", 1000);
    require(
        key != null && key.matches("[A-Za-z0-9-]{8,80}"),
        "idempotencyKey",
        "Supply a stable command key.");
    return writes.run(
        () -> {
          var before = env.self(s);
          db.lock(before.tenant());
          var a = env.self(s);
          if (!a.equals(before)) throw new CommandConflict("Identity changed. Reload.");
          var fp = fingerprint(List.of(k, recordId == null ? "" : recordId, input));
          var receipt = db.receipt(a.tenant(), a.subject(), key);
          if (receipt.isPresent()) {
            if (!receipt.get().fingerprint().equals(fp))
              throw new CommandConflict("This command key was already used with different input.");
            var result = db.decode(receipt.get().result(), type);
            readAccess(a, k, result);
            return result;
          }
          String previous = recordId == null ? "" : db.encode(load(a, k, recordId, type));
          var result = work.apply(a);
          var m = meta(result);
          if (recordId == null) db.insert(a.tenant(), k, m.id(), result);
          else db.update(a.tenant(), k, m.id(), m.version() - 1, result);
          var eventId = id();
          db.audit(
              a.tenant(),
              new Audit(
                  eventId,
                  k,
                  m.id(),
                  a.subject(),
                  a.employeeId(),
                  input.getClass().getSimpleName(),
                  reason,
                  previous,
                  db.encode(result),
                  now(),
                  correlation));
          var recipients = new HashSet<String>();
          recipients.add(m.preparedEmployee());
          switch (result) {
            case Review r -> {
              recipients.add(r.employeeId());
              if (m.status().equals("SUBMITTED")) {
                var worker = env.worker(s, r.employeeId());
                if (worker.managerId() != null && !worker.managerId().isBlank())
                  recipients.add(worker.managerId());
                recipients.addAll(env.hrReviewers(s));
              }
            }
            case Onboarding r -> recipients.add(r.employeeId());
            case Enrollment r -> recipients.add(r.employeeId());
            default -> {
              if (m.status().equals("SUBMITTED")) recipients.addAll(env.hrReviewers(s));
            }
          }
          for (var employee : recipients)
            db.notice(
                a.tenant(), new Notice(id(), eventId, k, m.id(), employee, m.status(), now()));
          db.receipt(a.tenant(), a.subject(), key, fp, result);
          return result;
        });
  }
}
