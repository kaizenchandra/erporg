package com.atlas.modules.talent.application.usecase;

import static com.atlas.modules.talent.domain.model.TalentPolicy.*;

import com.atlas.modules.talent.application.model.ReminderModels.*;
import com.atlas.modules.talent.application.model.TalentCommands.Actor;
import com.atlas.modules.talent.application.port.in.ReminderUseCases;
import com.atlas.modules.talent.application.port.out.TalentPorts.*;
import com.atlas.modules.talent.domain.model.TalentModels.*;
import com.atlas.shared.domain.*;
import java.time.*;
import java.util.*;

/**
 * Bounded in-app delivery; durable item receipts resume an interrupted batch without duplicates.
 */
public final class ReminderWorkflows extends TalentSupport implements ReminderUseCases {
  private final boolean schedulerEnabled;

  public ReminderWorkflows(
      Store d, Environment e, Transactions r, Transactions w, Clock c, boolean enabled) {
    super(d, e, r, w, c);
    schedulerEnabled = enabled;
  }

  private Actor actor(String s) {
    var a = env.self(s);
    hr(a);
    return a;
  }

  private void input(String reason, String key) {
    text(reason, "reason", 1000);
    require(
        key != null && key.matches("[a-zA-Z0-9-]{8,80}"),
        "idempotencyKey",
        "Supply a stable command key.");
  }

  public Context context(String s, int page) {
    require(page >= 0 && page <= 10000, "page", "Use a bounded job page.");
    return reads.run(
        () -> {
          var a = actor(s);
          return new Context(
              db.reminderPolicy(a.tenant()).orElse(null),
              db.reminderState(a.tenant()).orElse(null),
              db.reminderRuns(a.tenant(), page),
              schedulerEnabled);
        });
  }

  public Policy configure(String s, Configure c, String corr) {
    input(c.reason(), c.idempotencyKey());
    require(
        c.thresholdMinutes() >= 0
            && c.thresholdMinutes() <= 525600
            && c.frequencyMinutes() >= 1
            && c.frequencyMinutes() <= 525600,
        "minutes",
        "Set reminder age from 0–525600 minutes and frequency from 1–525600 minutes. No business deadline is assumed.");
    return writes.run(
        () -> {
          var a = actor(s);
          db.lock(a.tenant());
          if (!a.equals(actor(s))) throw new AccessDenied();
          var fp = fingerprint(List.of("reminder-policy", c));
          var receipt = db.receipt(a.tenant(), s, c.idempotencyKey());
          if (receipt.isPresent()) {
            if (!receipt.get().fingerprint().equals(fp))
              throw new CommandConflict("Command key already used for different data.");
            return db.decode(receipt.get().result(), Policy.class);
          }
          var old = db.reminderPolicy(a.tenant()).orElse(null);
          revision(old == null ? 0 : old.version(), c.version());
          var p =
              new Policy(
                  id(),
                  old == null ? 1 : old.version() + 1,
                  c.enabled(),
                  c.thresholdMinutes(),
                  c.frequencyMinutes(),
                  s,
                  a.employeeId(),
                  c.reason().strip(),
                  now(),
                  corr);
          db.reminderPolicy(a.tenant(), p);
          var prior = db.reminderState(a.tenant()).orElse(null);
          db.reminderState(
              a.tenant(),
              new State(
                  p.id(),
                  prior == null ? 0 : prior.version() + 1,
                  c.enabled() ? "READY" : "PAUSED",
                  0,
                  now()));
          db.receipt(a.tenant(), s, c.idempotencyKey(), fp, p);
          return p;
        });
  }

  private List<String> recipients(String s, String kind, Object record) {
    var m = meta(record);
    var result = new HashSet<>(env.hrReviewers(s));
    if (record instanceof Review review) {
      result.remove(review.employeeId());
      var worker = env.worker(s, review.employeeId());
      var manager = worker.managerId();
      if (manager != null
          && !manager.isBlank()
          && !manager.equals(review.employeeId())
          && env.hasRole(s, manager, "MANAGER")) {
        var w = env.worker(s, manager);
        if (Set.of("Active", "On leave").contains(w.status()) && !w.start().isAfter(today()))
          result.add(manager);
      }
    } else result.remove(m.preparedEmployee());
    return result.stream().sorted().toList();
  }

  private Run execute(
      Due due, String caller, boolean scheduled, String reason, String corr, String key) {
    return writes.run(
        () -> {
          var p = due.policy();
          var a = actor(p.owner());
          if (!a.tenant().equals(due.tenant()) || !a.employeeId().equals(p.ownerEmployee()))
            throw new AccessDenied();
          db.lock(a.tenant());
          if (!a.equals(actor(p.owner()))) throw new AccessDenied();
          var current = db.reminderPolicy(a.tenant()).orElseThrow(AccessDenied::new);
          if (!current.id().equals(p.id()) || !current.enabled())
            throw new CommandConflict("Reminder policy changed or is disabled.");
          var state = db.reminderState(a.tenant()).orElseThrow();
          if (!scheduled && !actor(caller).tenant().equals(due.tenant())) throw new AccessDenied();
          if (scheduled && state.nextDue().isAfter(now())) return null;
          if (!state.status().equals("READY"))
            throw new CommandConflict(
                "Reminder processing is paused or failed. Review and retry it explicitly.");
          var fp = fingerprint(List.of("reminder-run", p.id(), reason));
          if (key != null) {
            var replay = db.receipt(a.tenant(), caller, key);
            if (replay.isPresent()) {
              if (!replay.get().fingerprint().equals(fp))
                throw new CommandConflict("Run key already used for different data.");
              return db.decode(replay.get().result(), Run.class);
            }
          }
          var sources =
              db.reminderSources(
                  a.tenant(), now().minus(Duration.ofMinutes(p.thresholdMinutes())), today());
          var runId = id();
          int delivered = 0, skipped = 0;
          for (var source : sources) {
            var record = load(a, source.kind(), source.recordId(), type(source.kind()));
            var m = meta(record);
            if (!m.status().equals("SUBMITTED") || m.version() != source.version()) {
              db.reminderItem(
                  a.tenant(),
                  new Item(
                      id(),
                      runId,
                      source.eventId(),
                      source.kind(),
                      source.recordId(),
                      today(),
                      "SKIPPED",
                      "SOURCE_CHANGED",
                      0));
              skipped++;
              continue;
            }
            var ids = recipients(p.owner(), source.kind(), record);
            for (var employee : ids) {
              db.reminderNotice(
                  a.tenant(),
                  new Notice(
                      id(),
                      source.eventId(),
                      source.kind(),
                      source.recordId(),
                      employee,
                      "APPROVAL_REMINDER",
                      now()),
                  today(),
                  runId);
              delivered++;
            }
            if (ids.isEmpty()) skipped++;
            db.reminderItem(
                a.tenant(),
                new Item(
                    id(),
                    runId,
                    source.eventId(),
                    source.kind(),
                    source.recordId(),
                    today(),
                    ids.isEmpty() ? "SKIPPED" : "DELIVERED",
                    ids.isEmpty() ? "NO_CURRENT_INDEPENDENT_REVIEWER" : "IN_APP_COMMITTED",
                    ids.size()));
          }
          var run =
              new Run(
                  runId,
                  p.id(),
                  caller,
                  scheduled,
                  "COMMITTED",
                  sources.size(),
                  delivered,
                  skipped,
                  reason,
                  now(),
                  corr);
          db.reminderRun(a.tenant(), run);
          db.reminderState(
              a.tenant(),
              new State(
                  p.id(),
                  state.version() + 1,
                  "READY",
                  0,
                  sources.size() == 20
                      ? now()
                      : now().plus(Duration.ofMinutes(p.frequencyMinutes()))));
          if (key != null) db.receipt(a.tenant(), caller, key, fp, run);
          return run;
        });
  }

  public Run run(String s, Command c, String corr) {
    input(c.reason(), c.idempotencyKey());
    var due =
        reads.run(
            () -> {
              var a = actor(s);
              var p =
                  db.reminderPolicy(a.tenant())
                      .orElseThrow(
                          () ->
                              new CommandConflict(
                                  "Configure an authorized reminder policy first."));
              return new Due(a.tenant(), p);
            });
    return execute(due, s, false, c.reason().strip(), corr, c.idempotencyKey());
  }

  public State retry(String s, Command c, String corr) {
    input(c.reason(), c.idempotencyKey());
    return writes.run(
        () -> {
          var a = actor(s);
          db.lock(a.tenant());
          if (!a.equals(actor(s))) throw new AccessDenied();
          var fp = fingerprint(List.of("reminder-retry", c));
          var prior = db.receipt(a.tenant(), s, c.idempotencyKey());
          if (prior.isPresent()) {
            if (!prior.get().fingerprint().equals(fp))
              throw new CommandConflict("Retry key already used for different data.");
            return db.decode(prior.get().result(), State.class);
          }
          var p =
              db.reminderPolicy(a.tenant())
                  .orElseThrow(() -> new CommandConflict("Configure the policy first."));
          require(p.enabled(), "policy", "Enable a reviewed policy before replay.");
          var old = db.reminderState(a.tenant()).orElseThrow();
          var next = new State(p.id(), old.version() + 1, "READY", 0, now());
          db.reminderState(a.tenant(), next);
          db.reminderRun(
              a.tenant(),
              new Run(
                  id(),
                  p.id(),
                  s,
                  false,
                  "RETRY_REQUESTED",
                  0,
                  0,
                  0,
                  c.reason().strip(),
                  now(),
                  corr));
          db.receipt(a.tenant(), s, c.idempotencyKey(), fp, next);
          return next;
        });
  }

  public List<Item> items(String s, String run) {
    return reads.run(() -> db.reminderItems(actor(s).tenant(), run));
  }

  public void tick() {
    if (!schedulerEnabled) return;
    var due = reads.run(() -> db.reminderDue(now())).orElse(null);
    if (due == null) return;
    try {
      execute(
          due, "talent-reminder-scheduler", true, "Configured approval-age reminder", id(), null);
    } catch (RuntimeException failure) {
      writes.run(
          () -> {
            db.lock(due.tenant());
            var p = db.reminderPolicy(due.tenant()).orElse(null);
            if (p == null || !p.id().equals(due.policy().id())) return null;
            var state = db.reminderState(due.tenant()).orElseThrow();
            int failures = Math.min(3, state.failures() + 1);
            db.reminderState(
                due.tenant(),
                new State(
                    p.id(),
                    state.version() + 1,
                    failures >= 3 ? "FAILED" : "READY",
                    failures,
                    now().plusSeconds(60L * (1L << failures))));
            db.reminderRun(
                due.tenant(),
                new Run(
                    id(),
                    p.id(),
                    "talent-reminder-scheduler",
                    true,
                    "FAILED",
                    0,
                    0,
                    0,
                    "PROCESSING_FAILED: check the policy owner's current identity and database health; reconfigure or explicitly retry",
                    now(),
                    id()));
            return null;
          });
    }
  }
}
