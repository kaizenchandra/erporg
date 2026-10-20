package com.atlas.modules.timekeeping.application.usecase;

import static com.atlas.modules.timekeeping.application.usecase.TimeCommandRules.*;

import com.atlas.modules.timekeeping.application.model.TimeModels.*;
import com.atlas.modules.timekeeping.application.model.TimeModels.Period;
import com.atlas.modules.timekeeping.application.port.out.*;
import com.atlas.modules.timekeeping.domain.model.TimePolicy;
import com.atlas.shared.domain.*;
import java.time.*;
import java.util.*;

/** Weekly control orchestration; called within sheet transactions for consistent enforcement. */
final class TimePeriodService {
  private final TimeStore store;
  private final TimeWorkforce workforce;
  private final UnitOfWork reads, writes;
  private final Clock clock;

  TimePeriodService(
      TimeStore store, TimeWorkforce workforce, UnitOfWork reads, UnitOfWork writes, Clock clock) {
    this.store = store;
    this.workforce = workforce;
    this.reads = reads;
    this.writes = writes;
    this.clock = clock;
  }

  private Instant now() {
    return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  public PeriodContext period(String subject, LocalDate week) {
    TimePolicy.week(week);
    return reads.run(
        () -> {
          var a = workforce.self(subject);
          var history = store.periodHistory(a.tenant(), week);
          return new PeriodContext(history.isEmpty() ? null : history.getFirst(), history);
        });
  }

  public Period configurePeriod(String subject, SavePeriod c, String correlation) {
    return writes.run(
        () -> {
          var a = workforce.self(subject);
          if (!a.role().equals("HR")) throw new AccessDenied();
          active(a);
          TimePolicy.week(c.week());
          long version = TimePolicy.revision(c.version());
          var reason = TimePolicy.text(c.reason(), "reason", 500);
          key(c.idempotencyKey());
          TimePolicy.require(
              c.recallAllowed() != null,
              "recallAllowed",
              "Explicitly choose whether recall is allowed.");
          TimePolicy.require(
              c.closed() != null, "closed", "Explicitly choose whether this period is closed.");
          int daily;
          try {
            daily = TimePolicy.wholeMinutes(c.dailyLimit());
          } catch (InputValidation e) {
            throw new InputValidation(Map.of("dailyLimit", "Use 1–1440 whole minutes per day."));
          }
          TimePolicy.require(daily > 0, "dailyLimit", "Use 1–1440 whole minutes per day.");
          int weekly;
          try {
            weekly = c.weeklyLimit().intValueExact();
          } catch (ArithmeticException | NullPointerException e) {
            throw new InputValidation(
                Map.of("weeklyLimit", "Enter an exact whole-minute weekly limit."));
          }
          TimePolicy.require(
              weekly >= daily && weekly <= 10080,
              "weeklyLimit",
              "Daily limit must be positive; weekly limit must be at least daily and at most 10080 minutes.");
          TimePolicy.require(
              c.deadline() != null && c.deadline().getNano() % 1000 == 0,
              "deadline",
              "Supply a UTC deadline with at most six fractional digits.");
          TimePolicy.require(
              !c.deadline().isBefore(c.week().atStartOfDay(ZoneOffset.UTC).toInstant()),
              "deadline",
              "Deadline must not precede the reporting week.");
          store.lock(a.tenant(), "period-command/" + subject + "/" + c.idempotencyKey());
          store.lock(a.tenant(), "period/" + c.week());
          var currentActor = workforce.self(subject);
          sameIdentity(a, currentActor);
          if (!currentActor.role().equals("HR")) throw new AccessDenied();
          active(currentActor);
          var replay =
              store.periodReceipt(a.tenant(), a.subject(), c.idempotencyKey()).orElse(null);
          if (replay != null) {
            if (!replay.week().equals(c.week())
                || replay.version() != version + 1
                || !replay.deadline().equals(c.deadline())
                || replay.dailyLimit() != daily
                || replay.weeklyLimit() != weekly
                || replay.closed() != c.closed()
                || replay.recallAllowed() != c.recallAllowed()
                || !replay.reason().equals(reason))
              throw new CommandConflict("Command key already used for different period controls.");
            return replay;
          }
          var old = store.period(a.tenant(), c.week()).orElse(null);
          if (version != (old == null ? 0 : old.version()))
            throw new CommandConflict("Period controls changed; reload before saving.");
          TimePolicy.require(
              version < 1000,
              "version",
              "This period has reached the 1000-revision safety limit; contact support.");
          var changed =
              new Period(
                  c.week(),
                  c.deadline(),
                  daily,
                  weekly,
                  c.recallAllowed(),
                  c.closed(),
                  version + 1,
                  reason,
                  a.subject(),
                  now(),
                  c.idempotencyKey());
          store.savePeriod(a.tenant(), changed);
          store.audit(
              a.tenant(),
              a.employeeId(),
              "period/" + c.week(),
              "PERIOD_CONFIGURED",
              a.subject(),
              reason,
              old,
              changed,
              now(),
              correlation);
          return changed;
        });
  }

  void check(Actor a, Sheet sheet, String action, String correlation) {
    store.lock(a.tenant(), "period/" + sheet.week());
    var p = store.period(a.tenant(), sheet.week()).orElse(null);
    if (p == null) return; // Explicit legacy/local policy, visible in the UI.
    if (action.equals("RECALL")) {
      if (!p.recallAllowed())
        throw new CommandConflict(
            "Recall is disabled for this period; ask the reviewer to return it.");
    } else if (Set.of("SAVE", "SUBMIT", "APPROVE").contains(action)) {
      if (p.closed())
        throw new CommandConflict(
            "This reporting period is closed. Ask HR to reopen it with a reason.");
      if (action.equals("SUBMIT") && !now().isBefore(p.deadline()))
        throw new CommandConflict("Submission deadline has passed. Ask HR to extend the deadline.");
      TimePolicy.require(
          sheet.totalMinutes() <= p.weeklyLimit()
              && sheet.entries().stream().allMatch(e -> e.minutes() <= p.dailyLimit()),
          "entries",
          "Hours exceed configured daily or weekly limits. Review the period controls.");
    }
    store.audit(
        a.tenant(),
        sheet.employeeId(),
        sheet.id(),
        "PERIOD_CHECKED",
        a.subject(),
        "Period revision " + p.version() + " applied to " + action,
        null,
        p,
        now(),
        correlation);
  }
}
