package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.CalendarModels.*;
import com.atlas.modules.corehr.application.port.in.CalendarsUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;

public final class CalendarsService implements CalendarsUseCases {
  private final CalendarStore store;
  private final CalendarRetirementStore retirements;
  private final CalendarAmendmentStore amendments;
  private final UnitOfWork writes, reads;
  private final Clock clock;

  public CalendarsService(
      CalendarStore store,
      CalendarAmendmentStore amendments,
      CalendarRetirementStore retirements,
      UnitOfWork writes,
      UnitOfWork reads,
      Clock clock) {
    this.store = store;
    this.amendments = amendments;
    this.retirements = retirements;
    this.writes = writes;
    this.reads = reads;
    this.clock = clock;
  }

  private void requireHr(Actor actor) {
    if (!actor.hr()) throw new ChangeRequest.Denied();
  }

  public Page list(Actor a, Query q) {
    requireHr(a);
    return reads.run(() -> store.list(a.tenant(), q, LocalDate.now(clock)));
  }

  public List<String> timeZones(Actor a) {
    requireHr(a);
    return ZoneId.getAvailableZoneIds().stream().sorted().toList();
  }

  public Entry detail(Actor a, String code) {
    requireHr(a);
    if (code == null || !code.matches("[A-Z0-9-]{2,30}"))
      throw new IllegalArgumentException("Choose a valid calendar code.");
    return reads.run(() -> store.find(a.tenant(), code).orElseThrow(ChangeRequest.Denied::new));
  }

  public Preview preview(Actor a, String code, String value) {
    requireHr(a);
    var date = WorkCalendar.date(value, "date");
    return reads.run(
        () -> {
          var c = detail(a, code).calendar();
          c.classification(date);
          if (retirements
              .find(a.tenant(), code)
              .filter(e -> e.retirement().applies(date))
              .isPresent())
            throw new ChangeRequest.Conflict(
                "Calendar is retired for this date. Choose an earlier date or another calendar.");
          var selected =
              CalendarRevision.select(
                  c,
                  amendments.history(a.tenant(), c).stream()
                      .map(
                          com.atlas.modules.corehr.application.model.CalendarAmendmentModels
                                  .RevisionEntry
                              ::revision)
                      .toList(),
                  date);
          var classification = selected.policy().classification(date);
          return new Preview(
              c.code(),
              date,
              c.timeZone(),
              classification,
              classification.equals("WORKING_DAY"),
              selected.version(),
              selected.effectiveDate());
        });
  }

  public Entry create(Actor a, Create command, String correlation) {
    requireHr(a);
    var errors = new LinkedHashMap<String, String>();
    if (command.calendar() == null) errors.put("calendar", "Enter the company calendar.");
    if (command.reason() == null
        || command.reason().isBlank()
        || command.reason().length() > 500
        || command.reason().codePoints().anyMatch(Character::isISOControl))
      errors.put("reason", "Enter a reason of 1–500 characters without control characters.");
    if (command.idempotencyKey() == null || !command.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      errors.put("idempotencyKey", "A valid command key is required.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
    var input = command.calendar();
    var from = WorkCalendar.date(input.effectiveFrom(), "effectiveFrom");
    var until = WorkCalendar.date(input.effectiveUntil(), "effectiveUntil");
    if (input.closedDates() == null || input.closedDates().size() > 50)
      throw new ChangeRequest.Validation(Map.of("closedDates", "Enter at most 50 closure dates."));
    var closures =
        input.closedDates().stream().map(d -> WorkCalendar.date(d, "closedDates")).toList();
    var c =
        new WorkCalendar(
            input.code(),
            input.name(),
            input.timeZone(),
            from,
            until,
            com.atlas.modules.corehr.application.model.CalendarModels.wholeDays(
                input.workingDays()),
            closures);
    return writes.run(
        () -> {
          store.lock(a.tenant());
          var previous = store.duplicate(a.tenant(), a.subject(), command.idempotencyKey());
          if (previous.isPresent()) {
            var p = previous.get();
            if (!p.calendar().equals(c) || !p.reason().equals(command.reason().trim()))
              throw new ChangeRequest.Conflict("Command key was used for different calendar data.");
            return p;
          }
          c.validateNew(LocalDate.now(clock));
          if (store.find(a.tenant(), c.code()).isPresent())
            throw new ChangeRequest.Conflict("This calendar code is already recorded.");
          if (store.count(a.tenant()) >= 200)
            throw new ChangeRequest.Conflict(
                "The provisional limit of 200 calendars has been reached.");
          var entry =
              new Entry(
                  c,
                  a.subject(),
                  command.reason().trim(),
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  correlation);
          store.insert(a.tenant(), entry, command.idempotencyKey());
          return entry;
        });
  }
}
