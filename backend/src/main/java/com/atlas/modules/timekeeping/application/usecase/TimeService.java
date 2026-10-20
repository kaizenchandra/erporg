package com.atlas.modules.timekeeping.application.usecase;

import static com.atlas.modules.timekeeping.application.usecase.TimeCommandRules.*;

import com.atlas.modules.timekeeping.application.model.TimeModels.*;
import com.atlas.modules.timekeeping.application.model.TimeModels.Period;
import com.atlas.modules.timekeeping.application.port.in.TimeUseCases;
import com.atlas.modules.timekeeping.application.port.out.*;
import com.atlas.modules.timekeeping.domain.model.TimePolicy;
import com.atlas.shared.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

public final class TimeService implements TimeUseCases {
  private final TimeStore store;
  private final TimeWorkforce workforce;
  private final UnitOfWork reads, writes;
  private final Clock clock;
  private final TimePeriodService periods;
  private final TimeReportService reports;

  public TimeService(
      TimeStore store, TimeWorkforce workforce, UnitOfWork reads, UnitOfWork writes, Clock clock) {
    this.store = store;
    this.workforce = workforce;
    this.reads = reads;
    this.writes = writes;
    this.clock = clock;
    this.periods = new TimePeriodService(store, workforce, reads, writes, clock);
    this.reports = new TimeReportService(store, workforce, reads, writes, clock);
  }

  private Instant now() {
    return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  private void page(int page, int size) {
    TimePolicy.require(
        page >= 0 && page <= 100000 && size >= 1 && size <= 50,
        "page",
        "Use page0–100000 and size1–50.");
  }

  private Actor lockedSelf(Actor before, String subject) {
    store.lock(before.tenant(), before.employeeId());
    var after = workforce.self(subject);
    sameIdentity(before, after);
    return after;
  }

  private String fingerprint(Object... values) {
    try {
      var encoded = new StringBuilder();
      for (var v : values) {
        var s = String.valueOf(v);
        encoded.append(s.length()).append(':').append(s);
      }
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encoded.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Outcome replay(
      Actor a, String key, String fingerprint, String employeeId, LocalDate episodeStart) {
    var r = store.receipt(a.tenant(), a.subject(), key(key));
    if (r.isEmpty()) return null;
    var result = r.get().outcome();
    if (result == null || (result.shift() == null && result.sheet() == null))
      throw new CommandConflict("Recorded command result is unavailable; contact support.");
    if ((result.shift() != null
            && (!result.shift().employeeId().equals(employeeId)
                || !result.shift().episodeStart().equals(episodeStart)))
        || (result.sheet() != null
            && (!result.sheet().employeeId().equals(employeeId)
                || !result.sheet().episodeStart().equals(episodeStart)))) throw new AccessDenied();
    if (!r.get().fingerprint().equals(fingerprint))
      throw new CommandConflict("Command key already used for different data.");
    return r.get().outcome();
  }

  private void sameEpisode(LocalDate actual, Actor a) {
    if (!actual.equals(a.start()))
      throw new CommandConflict(
          "This record belongs to an earlier employment episode and cannot be changed.");
  }

  public PeriodContext period(String subject, LocalDate week) {
    return periods.period(subject, week);
  }

  public Period configurePeriod(String subject, SavePeriod command, String correlation) {
    return periods.configurePeriod(subject, command, correlation);
  }

  public Page<Shift> attendance(String subject, String employeeId, int page, int size) {
    page(page, size);
    return reads.run(
        () -> {
          var a = workforce.employee(subject, employeeId);
          return store.shifts(a.tenant(), employeeId, page, size);
        });
  }

  public Page<Audit> history(String subject, String employeeId, int page, int size) {
    page(page, size);
    return reads.run(
        () -> {
          var a = workforce.employee(subject, employeeId);
          return store.history(a.tenant(), employeeId, page, size);
        });
  }

  public Shift punch(String subject, String action, Punch c, String correlation) {
    return writes.run(
        () -> {
          var a = workforce.self(subject);
          active(a);
          var zone = TimePolicy.zone(c.zone());
          TimePolicy.require(Set.of("IN", "OUT").contains(action), "action", "Choose IN or OUT.");
          a = lockedSelf(a, subject);
          active(a);
          var fp = fingerprint("PUNCH", action, zone.getId());
          var oldResult = replay(a, c.idempotencyKey(), fp, a.employeeId(), a.start());
          if (oldResult != null) return oldResult.shift();
          var time = now();
          var old = store.openShift(a.tenant(), a.employeeId(), a.start()).orElse(null);
          Shift changed;
          if (action.equals("IN")) {
            if (old != null)
              throw new CommandConflict(
                  "A shift is already open. Clock out or correct the missing entry.");
            TimePolicy.interval(time, null, time, a.start(), zone);
            if (store.overlaps(a.tenant(), a.employeeId(), a.start(), "", time, null))
              throw new CommandConflict("Attendance overlaps another shift.");
            changed =
                new Shift(
                    UUID.randomUUID().toString(),
                    a.employeeId(),
                    a.start(),
                    time,
                    null,
                    zone.getId(),
                    0,
                    "Clock in",
                    time);
          } else {
            if (old == null)
              throw new CommandConflict("No open shift. Record a missed shift correction instead.");
            if (!old.zone().equals(zone.getId()))
              throw new CommandConflict("Use the open shift time zone when clocking out.");
            TimePolicy.interval(old.start(), time, time, a.start(), zone);
            changed =
                new Shift(
                    old.id(),
                    a.employeeId(),
                    a.start(),
                    old.start(),
                    time,
                    old.zone(),
                    old.version() + 1,
                    "Clock out",
                    time);
          }
          store.saveShift(a.tenant(), changed);
          store.audit(
              a.tenant(),
              a.employeeId(),
              changed.id(),
              "CLOCK_" + action,
              a.subject(),
              changed.reason(),
              old,
              changed,
              time,
              correlation);
          store.remember(
              a.tenant(), a.subject(), c.idempotencyKey(), fp, new Outcome(changed, null));
          return changed;
        });
  }

  public Shift correct(String subject, Correction c, String correlation) {
    return writes.run(
        () -> {
          var a = workforce.self(subject);
          active(a);
          var version = TimePolicy.revision(c.version());
          var zone = TimePolicy.zone(c.zone());
          var start = TimePolicy.timestamp(c.start(), zone, "start");
          var end = TimePolicy.timestamp(c.end(), zone, "end");
          var reason = TimePolicy.text(c.reason(), "reason", 500);
          var id = c.id() == null ? "" : c.id();
          a = lockedSelf(a, subject);
          active(a);
          var fp = fingerprint("CORRECT", id, version, start, end, zone.getId(), reason);
          var replay = replay(a, c.idempotencyKey(), fp, a.employeeId(), a.start());
          if (replay != null) return replay.shift();
          Shift old = null;
          if (!id.isEmpty()) {
            old = store.shift(a.tenant(), id).orElseThrow(AccessDenied::new);
            if (!old.employeeId().equals(a.employeeId())) throw new AccessDenied();
            sameEpisode(old.episodeStart(), a);
            if (old.version() != version)
              throw new CommandConflict("Attendance changed; reload before correcting.");
          } else if (version != 0) throw new CommandConflict("A missed shift starts at version0.");
          var time = now();
          TimePolicy.interval(start, end, time, a.start(), zone);
          if (store.overlaps(a.tenant(), a.employeeId(), a.start(), id, start, end))
            throw new CommandConflict("Attendance overlaps an existing shift.");
          var changed =
              new Shift(
                  old == null ? UUID.randomUUID().toString() : id,
                  a.employeeId(),
                  a.start(),
                  start,
                  end,
                  zone.getId(),
                  old == null ? 0 : old.version() + 1,
                  reason,
                  time);
          store.saveShift(a.tenant(), changed);
          store.audit(
              a.tenant(),
              a.employeeId(),
              changed.id(),
              "CORRECTED",
              a.subject(),
              reason,
              old,
              changed,
              time,
              correlation);
          store.remember(
              a.tenant(), a.subject(), c.idempotencyKey(), fp, new Outcome(changed, null));
          return changed;
        });
  }

  public Page<Sheet> sheets(String subject, boolean review, String status, int page, int size) {
    page(page, size);
    TimePolicy.require(
        Set.of("ALL", "DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "RETURNED", "CORRECTED")
            .contains(status),
        "status",
        "Choose a valid status.");
    return reads.run(
        () -> {
          var a = workforce.self(subject);
          if (!review)
            return store.sheets(a.tenant(), List.of(a.employeeId()), false, "", status, page, size);
          var scope = workforce.scope(subject);
          return store.sheets(
              a.tenant(), scope.employeeIds(), scope.all(), a.employeeId(), status, page, size);
        });
  }

  public Sheet approvedSource(String subject, String id) {
    var source = sheet(subject, id).sheet();
    TimePolicy.approvedSource(source.status());
    return source;
  }

  public SheetContext sheet(String subject, String id) {
    return reads.run(
        () -> {
          var a = workforce.self(subject);
          var s = store.sheet(a.tenant(), id).orElseThrow(AccessDenied::new);
          workforce.employee(subject, s.employeeId());
          var allocations = store.allocations(a.tenant(), id);
          return new SheetContext(
              s, allocations, allocations.stream().mapToInt(Allocation::minutes).sum());
        });
  }

  private List<Entry> entries(
      List<EntryInput> inputs, LocalDate week, ZoneId zone, Actor a, Instant now) {
    TimePolicy.require(
        inputs != null && inputs.size() == 7,
        "entries",
        "Provide exactly seven date entries, including zero-hour days.");
    var dates = new HashSet<LocalDate>();
    var result = new ArrayList<Entry>();
    var today = now.atZone(zone).toLocalDate();
    for (var input : inputs) {
      TimePolicy.require(
          input != null
              && input.date() != null
              && !input.date().isBefore(week)
              && input.date().isBefore(week.plusDays(7))
              && dates.add(input.date()),
          "entries",
          "Each day of the selected week must occur once.");
      TimePolicy.require(
          input.minutes() == null || input.hours() == null,
          "entries",
          "Supply minutes or hours, not both.");
      int minutes;
      if (input.minutes() != null) minutes = TimePolicy.wholeMinutes(input.minutes());
      else minutes = TimePolicy.minutes(input.hours());
      String code = minutes == 0 ? "" : TimePolicy.text(input.code(), "code", 80);
      if (minutes > 0)
        TimePolicy.require(
            !input.date().isAfter(today) && !input.date().isBefore(a.start()),
            "entries",
            "Positive time must be within the current employment episode and not in the future.");
      result.add(new Entry(input.date(), minutes, code));
    }
    result.sort(Comparator.comparing(Entry::date));
    return List.copyOf(result);
  }

  public Dashboard dashboard(String subject, LocalDate week) {
    TimePolicy.week(week);
    return reads.run(
        () -> {
          var a = workforce.self(subject);
          var mine = store.week(a.tenant(), a.employeeId(), week, a.start()).orElse(null);
          var canReview = Set.of("HR", "MANAGER").contains(a.role());
          long pending = 0;
          if (canReview) {
            var scope = workforce.scope(subject);
            pending =
                store
                    .sheets(
                        a.tenant(),
                        scope.employeeIds(),
                        scope.all(),
                        a.employeeId(),
                        "SUBMITTED",
                        0,
                        1)
                    .total();
          }
          return new Dashboard(
              week, mine, store.period(a.tenant(), week).orElse(null), pending, canReview, now());
        });
  }

  public Report report(String subject, ReportQuery query) {
    return reports.report(subject, query);
  }

  public Report exportReport(String subject, ReportQuery query, String correlation) {
    return reports.exportReport(subject, query, correlation);
  }

  public Sheet copy(String subject, CopySheet c, String correlation) {
    return writes.run(
        () -> {
          var a = lockedSelf(workforce.self(subject), subject);
          active(a);
          TimePolicy.week(c.week());
          TimePolicy.text(c.sourceId(), "sourceId", 40);
          var sourceVersion = TimePolicy.revision(c.sourceVersion());
          var reason = TimePolicy.text(c.reason(), "reason", 500);
          var fp = fingerprint("COPY", c.sourceId(), sourceVersion, c.week(), reason, a.start());
          var replay = replay(a, c.idempotencyKey(), fp, a.employeeId(), a.start());
          if (replay != null) return replay.sheet();
          var source = store.sheet(a.tenant(), c.sourceId()).orElseThrow(AccessDenied::new);
          if (!source.employeeId().equals(a.employeeId())) throw new AccessDenied();
          sameEpisode(source.episodeStart(), a);
          if (source.version() != sourceVersion)
            throw new CommandConflict("Source timesheet changed; reload before copying.");
          TimePolicy.require(
              c.week().isAfter(source.week()), "week", "Copy only to a later reporting week.");
          if (store.week(a.tenant(), a.employeeId(), c.week(), a.start()).isPresent())
            throw new CommandConflict(
                "A timesheet already exists for the target week. Open it instead; copying never overwrites entries.");
          var inputs =
              source.entries().stream()
                  .map(
                      e ->
                          new EntryInput(
                              c.week()
                                  .plusDays(
                                      java.time.temporal.ChronoUnit.DAYS.between(
                                          source.week(), e.date())),
                              null,
                              e.code(),
                              java.math.BigDecimal.valueOf(e.minutes())))
                  .toList();
          var time = now();
          var entries = entries(inputs, c.week(), TimePolicy.zone(source.zone()), a, time);
          var changed =
              new Sheet(
                  UUID.randomUUID().toString(),
                  a.employeeId(),
                  a.start(),
                  c.week(),
                  source.zone(),
                  "DRAFT",
                  0,
                  entries,
                  entries.stream().mapToInt(Entry::minutes).sum(),
                  reason,
                  null,
                  null);
          periods.check(a, changed, "SAVE", correlation);
          store.saveSheet(a.tenant(), changed);
          store.audit(
              a.tenant(),
              a.employeeId(),
              changed.id(),
              "SAVED",
              a.subject(),
              reason,
              null,
              changed,
              time,
              correlation);
          store.audit(
              a.tenant(),
              a.employeeId(),
              changed.id(),
              "COPIED",
              a.subject(),
              reason,
              Map.of("sourceId", source.id(), "sourceVersion", source.version()),
              changed,
              time,
              correlation);
          store.remember(
              a.tenant(), a.subject(), c.idempotencyKey(), fp, new Outcome(null, changed));
          return changed;
        });
  }

  public Sheet save(String subject, SaveSheet c, String correlation) {
    return writes.run(
        () -> {
          var a = workforce.self(subject);
          active(a);
          TimePolicy.week(c.week());
          var zone = TimePolicy.zone(c.zone());
          var version = TimePolicy.revision(c.version());
          var reason = TimePolicy.text(c.reason(), "reason", 500);
          a = lockedSelf(a, subject);
          active(a);
          var time = now();
          var entries = entries(c.entries(), c.week(), zone, a, time);
          var fp = fingerprint("SAVE", a.start(), c.week(), zone.getId(), version, entries, reason);
          var replay = replay(a, c.idempotencyKey(), fp, a.employeeId(), a.start());
          if (replay != null) return replay.sheet();
          var old = store.week(a.tenant(), a.employeeId(), c.week(), a.start()).orElse(null);
          if (old != null) {
            TimePolicy.editable(old.status());
            if (old.version() != version)
              throw new CommandConflict("Timesheet changed; reload before saving.");
          } else if (version != 0) throw new CommandConflict("A new timesheet starts at version0.");
          var changed =
              new Sheet(
                  old == null ? UUID.randomUUID().toString() : old.id(),
                  a.employeeId(),
                  a.start(),
                  c.week(),
                  zone.getId(),
                  old != null && Set.of("RETURNED", "CORRECTED").contains(old.status())
                      ? "CORRECTED"
                      : "DRAFT",
                  old == null ? 0 : old.version() + 1,
                  entries,
                  entries.stream().mapToInt(Entry::minutes).sum(),
                  reason,
                  null,
                  null);
          periods.check(a, changed, "SAVE", correlation);
          store.saveSheet(a.tenant(), changed);
          store.audit(
              a.tenant(),
              a.employeeId(),
              changed.id(),
              "SAVED",
              a.subject(),
              reason,
              old,
              changed,
              time,
              correlation);
          store.remember(
              a.tenant(), a.subject(), c.idempotencyKey(), fp, new Outcome(null, changed));
          return changed;
        });
  }

  public List<Sheet> decideSelected(String subject, BulkDecision c, String correlation) {
    return writes.run(
        () -> {
          TimePolicy.require(
              c.sheets() != null && !c.sheets().isEmpty() && c.sheets().size() <= 20,
              "sheets",
              "Select 1–20 timesheets; this action never applies to all filtered results.");
          TimePolicy.require(
              c.action() != null && Set.of("APPROVE", "RETURN", "REJECT").contains(c.action()),
              "action",
              "Choose APPROVE, RETURN or REJECT for selected sheets.");
          key(c.idempotencyKey());
          TimePolicy.require(
              c.idempotencyKey().length() <= 47,
              "idempotencyKey",
              "Bulk command keys must be at most 47 characters.");
          var actor = workforce.self(subject);
          store.lock(actor.tenant(), "batch/" + subject + "/" + c.idempotencyKey());
          var employees = new TreeSet<String>();
          var ids = new HashSet<String>();
          for (var item : c.sheets()) {
            TimePolicy.require(
                item != null && item.id() != null && ids.add(item.id()),
                "sheets",
                "Select each timesheet exactly once.");
            var s = store.sheet(actor.tenant(), item.id()).orElseThrow(AccessDenied::new);
            workforce.employee(subject, s.employeeId());
            employees.add(s.employeeId());
          }
          for (var employee : employees) store.lock(actor.tenant(), employee);
          sameIdentity(actor, workforce.self(subject));
          var selected =
              c.sheets().stream().sorted(Comparator.comparing(SelectedSheet::id)).toList();
          var fp = fingerprint(selected, c.action(), c.comment());
          var previous = store.batchFingerprint(actor.tenant(), subject, c.idempotencyKey());
          if (previous.isPresent() && !previous.get().equals(fp))
            throw new CommandConflict(
                "Bulk command key already used for a different selection or decision.");
          var result = new ArrayList<Sheet>();
          for (var item : selected) {
            // Resource-specific receipt keys remain stable if the caller retries the same
            // selection.
            var suffix = fingerprint(item.id()).substring(0, 32);
            result.add(
                decide(
                    subject,
                    item.id(),
                    new Decision(
                        item.version(), c.action(), c.comment(), c.idempotencyKey() + "-" + suffix),
                    correlation));
          }
          if (previous.isEmpty())
            store.rememberBatch(actor.tenant(), subject, c.idempotencyKey(), fp);
          return List.copyOf(result);
        });
  }

  public Sheet decide(String subject, String id, Decision c, String correlation) {
    return writes.run(
        () -> {
          var actor = workforce.self(subject);
          var old = store.sheet(actor.tenant(), id).orElseThrow(AccessDenied::new);
          workforce.employee(subject, old.employeeId());
          store.lock(actor.tenant(), old.employeeId());
          var beforeLockActor = actor;
          actor = workforce.self(subject);
          sameIdentity(beforeLockActor, actor);
          var employee = workforce.employee(subject, old.employeeId());
          old = store.sheet(actor.tenant(), id).orElseThrow(AccessDenied::new);
          var version = TimePolicy.revision(c.version());
          var comment = TimePolicy.text(c.comment(), "comment", 500);
          var action = c.action();
          TimePolicy.require(
              action != null
                  && Set.of("SUBMIT", "RECALL", "APPROVE", "RETURN", "REJECT").contains(action),
              "action",
              "Choose SUBMIT, RECALL, APPROVE, RETURN or REJECT.");
          boolean owner = actor.employeeId().equals(old.employeeId());
          var subjectAtReview = actor.subject();
          boolean contributed =
              store.actors(actor.tenant(), id).stream()
                  .anyMatch(
                      event ->
                          event.actor().equals(subjectAtReview)
                              && Set.of("SAVED", "SUBMIT", "RECALL").contains(event.action()));
          boolean reviewer =
              !owner && !contributed && Set.of("HR", "MANAGER").contains(actor.role());
          if (Set.of("SUBMIT", "RECALL").contains(action) ? !owner : !reviewer)
            throw new AccessDenied();
          var fp = fingerprint("DECIDE", id, version, action, comment);
          var replay = replay(actor, c.idempotencyKey(), fp, old.employeeId(), old.episodeStart());
          if (replay != null) return replay.sheet();
          sameEpisode(old.episodeStart(), employee);
          active(employee);
          if (old.version() != version)
            throw new CommandConflict("Timesheet changed; reload before deciding.");
          var time = now();
          // Existing minute values are authoritative; only their business dates need revalidation
          // here.
          for (var e : old.entries())
            if (e.minutes() > 0)
              TimePolicy.require(
                  !e.date().isAfter(time.atZone(ZoneId.of(old.zone())).toLocalDate())
                      && !e.date().isBefore(employee.start()),
                  "entries",
                  "Stored hours are outside this employment period or in the future.");
          var status = TimePolicy.transition(old.status(), action, old.totalMinutes(), reviewer);
          var changed =
              new Sheet(
                  old.id(),
                  old.employeeId(),
                  old.episodeStart(),
                  old.week(),
                  old.zone(),
                  status,
                  old.version() + 1,
                  old.entries(),
                  old.totalMinutes(),
                  old.reason(),
                  reviewer ? actor.subject() : null,
                  reviewer ? comment : null);
          periods.check(actor, changed, action, correlation);
          store.saveSheet(actor.tenant(), changed);
          if (status.equals("APPROVED")) {
            store.allocate(actor.tenant(), changed, time);
            if (store.allocations(actor.tenant(), id).stream().mapToInt(Allocation::minutes).sum()
                != changed.totalMinutes())
              throw new CommandConflict("Approved-hours allocation failed reconciliation.");
          }
          store.audit(
              actor.tenant(),
              old.employeeId(),
              id,
              action,
              actor.subject(),
              comment,
              old,
              changed,
              time,
              correlation);
          store.remember(
              actor.tenant(), actor.subject(), c.idempotencyKey(), fp, new Outcome(null, changed));
          return changed;
        });
  }
}
