package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.CalendarRetirementModels.*;
import com.atlas.modules.corehr.application.port.in.CalendarRetirementUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;

public final class CalendarRetirementService implements CalendarRetirementUseCases {
  private final CalendarStore calendars;
  private final CalendarAmendmentStore amendments;
  private final CalendarRetirementStore retirements;
  private final UnitOfWork writes;
  private final Clock clock;

  public CalendarRetirementService(
      CalendarStore calendars,
      CalendarAmendmentStore amendments,
      CalendarRetirementStore retirements,
      UnitOfWork writes,
      Clock clock) {
    this.calendars = calendars;
    this.amendments = amendments;
    this.retirements = retirements;
    this.writes = writes;
    this.clock = clock;
  }

  public Entry retire(Actor actor, String code, Retire c, String correlation) {
    if (!actor.hr()) throw new ChangeRequest.Denied();
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
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
    var retirement =
        new CalendarRetirement(
            code, version, WorkCalendar.date(c.effectiveDate(), "effectiveDate"));
    return writes.run(
        () -> {
          calendars.lock(actor.tenant());
          var base =
              calendars
                  .find(actor.tenant(), code)
                  .orElseThrow(ChangeRequest.Denied::new)
                  .calendar();
          var replay = retirements.duplicate(actor.tenant(), actor.subject(), c.idempotencyKey());
          if (replay.isPresent()) {
            var original = replay.get();
            if (!original.retirement().equals(retirement)
                || !original.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict(
                  "Command key was used for different retirement data.");
            return original;
          }
          if (retirements.find(actor.tenant(), code).isPresent())
            throw new ChangeRequest.Conflict("Calendar retirement is already recorded.");
          if (calendars.assignmentRecorded(actor.tenant(), code))
            throw new ChangeRequest.Conflict(
                "Calendar has approved assignment history. Retirement is frozen.");
          var history = amendments.history(actor.tenant(), base);
          retirement.validateNew(
              base, history.isEmpty() ? null : history.getLast().revision(), LocalDate.now(clock));
          var entry =
              new Entry(
                  retirement,
                  actor.subject(),
                  c.reason().trim(),
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  correlation);
          retirements.append(actor.tenant(), entry, c.idempotencyKey());
          return entry;
        });
  }
}
