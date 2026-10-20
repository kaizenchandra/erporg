package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.CalendarAmendmentModels.*;
import com.atlas.modules.corehr.application.model.CalendarModels;
import com.atlas.modules.corehr.application.port.in.CalendarAmendmentsUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;

public final class CalendarAmendmentsService implements CalendarAmendmentsUseCases {
  private final CalendarStore calendars;
  private final CalendarRetirementStore retirements;
  private final CalendarAmendmentStore amendments;
  private final UnitOfWork reads, writes;
  private final Clock clock;

  public CalendarAmendmentsService(
      CalendarStore calendars,
      CalendarAmendmentStore amendments,
      CalendarRetirementStore retirements,
      UnitOfWork writes,
      UnitOfWork reads,
      Clock clock) {
    this.calendars = calendars;
    this.amendments = amendments;
    this.retirements = retirements;
    this.writes = writes;
    this.reads = reads;
    this.clock = clock;
  }

  private void scope(Actor a, String code) {
    if (!a.hr()) throw new ChangeRequest.Denied();
    if (code == null || !code.matches("[A-Z0-9-]{2,30}"))
      throw new IllegalArgumentException("Choose a valid calendar code.");
  }

  public Context context(Actor a, String code) {
    scope(a, code);
    return reads.run(
        () -> {
          var base = calendars.find(a.tenant(), code).orElseThrow(ChangeRequest.Denied::new);
          var history = amendments.history(a.tenant(), base.calendar());
          var last = history.isEmpty() ? null : history.getLast().revision();
          var today = LocalDate.now(clock);
          var selected =
              CalendarRevision.select(
                  base.calendar(), history.stream().map(RevisionEntry::revision).toList(), today);
          var next =
              last == null ? base.calendar().effectiveFrom() : last.effectiveDate().plusDays(1);
          if (next.isBefore(today)) next = today;
          var retirement = retirements.find(a.tenant(), code).orElse(null);
          boolean assigned = calendars.assignmentRecorded(a.tenant(), code);
          Draft draft = null;
          if (!assigned
              && retirement == null
              && history.size() < 20
              && !next.isAfter(base.calendar().effectiveUntil())
              && !next.isAfter(today.plusYears(1))) {
            var policy = last == null ? base.calendar() : last.policy();
            var from = next;
            draft =
                new Draft(
                    next,
                    policy.workingDays(),
                    policy.closedDates().stream().filter(d -> !d.isBefore(from)).toList());
          }
          return new Context(
              base,
              selected.policy(),
              selected.version(),
              selected.effectiveDate(),
              history.size(),
              history,
              draft,
              retirement,
              retirement == null
                  ? "NONE"
                  : retirement.retirement().applies(today) ? "RETIRED" : "SCHEDULED",
              !assigned
                      && retirement == null
                      && !next.isAfter(base.calendar().effectiveUntil())
                      && !next.isAfter(today.plusYears(1))
                  ? next
                  : null,
              assigned);
        });
  }

  public RevisionEntry amend(Actor a, String code, Amend c, String correlation) {
    scope(a, code);
    var errors = new LinkedHashMap<String, String>();
    int version = -1;
    try {
      if (c.version() != null) version = c.version().intValueExact();
    } catch (ArithmeticException ignored) {
    }
    if (version < 0 || version > 20)
      errors.put("version", "Use an exact current calendar version.");
    if (c.reason() == null
        || c.reason().isBlank()
        || c.reason().length() > 500
        || c.reason().codePoints().anyMatch(Character::isISOControl))
      errors.put("reason", "Enter a reason of 1–500 characters without controls.");
    if (c.idempotencyKey() == null || !c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      errors.put("idempotencyKey", "A valid command key is required.");
    if (c.closedDates() == null || c.closedDates().size() > 50)
      errors.put("closedDates", "Enter at most 50 closure dates.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
    var date = WorkCalendar.date(c.effectiveDate(), "effectiveDate");
    var days = CalendarModels.wholeDays(c.workingDays());
    var closed = c.closedDates().stream().map(d -> WorkCalendar.date(d, "closedDates")).toList();
    int expected = version;
    return writes.run(
        () -> {
          calendars.lock(a.tenant());
          var base =
              calendars.find(a.tenant(), code).orElseThrow(ChangeRequest.Denied::new).calendar();
          if (expected >= 20)
            throw new ChangeRequest.Conflict(
                "The limit of twenty calendar amendments has been reached.");
          var policy =
              new WorkCalendar(
                  base.code(),
                  base.name(),
                  base.timeZone(),
                  base.effectiveFrom(),
                  base.effectiveUntil(),
                  days,
                  closed);
          var proposed = new CalendarRevision(expected + 1, date, policy);
          var duplicate = amendments.duplicate(a.tenant(), a.subject(), c.idempotencyKey());
          if (duplicate.isPresent()) {
            var previous = duplicate.get();
            if (!previous.revision().equals(proposed)
                || !previous.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict(
                  "Command key was used for different calendar amendment data.");
            return previous;
          }
          if (calendars.assignmentRecorded(a.tenant(), code))
            throw new ChangeRequest.Conflict(
                "Calendar has approved assignment history. New policy amendments are frozen.");
          if (retirements.find(a.tenant(), code).isPresent())
            throw new ChangeRequest.Conflict(
                "Calendar retirement is recorded. New amendments are unavailable.");
          var history = amendments.history(a.tenant(), base);
          if (history.size() != expected)
            throw new ChangeRequest.Conflict(
                "Calendar changed. Reopen its latest context before amending.");
          proposed.validateNew(
              history.isEmpty() ? null : history.getLast().revision(), LocalDate.now(clock));
          var entry =
              new RevisionEntry(
                  proposed,
                  a.subject(),
                  c.reason().trim(),
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  correlation);
          amendments.append(a.tenant(), entry, c.idempotencyKey());
          return entry;
        });
  }
}
