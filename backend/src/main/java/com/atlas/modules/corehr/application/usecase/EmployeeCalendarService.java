package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.CalendarModels;
import com.atlas.modules.corehr.application.model.EmployeeCalendarModels.*;
import com.atlas.modules.corehr.application.port.in.EmployeeCalendarUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;

public final class EmployeeCalendarService implements EmployeeCalendarUseCases {
  private final PeopleStore people;
  private final CalendarStore calendars;
  private final CalendarAmendmentStore amendments;
  private final CalendarRetirementStore retirements;
  private final EmployeeCalendarHistoryStore history;
  private final UnitOfWork reads;
  private final Clock clock;

  public EmployeeCalendarService(
      PeopleStore people,
      CalendarStore calendars,
      CalendarAmendmentStore amendments,
      CalendarRetirementStore retirements,
      EmployeeCalendarHistoryStore history,
      UnitOfWork reads,
      Clock clock) {
    this.people = people;
    this.calendars = calendars;
    this.amendments = amendments;
    this.retirements = retirements;
    this.history = history;
    this.reads = reads;
    this.clock = clock;
  }

  public History history(Actor actor, String id, HistoryQuery query) {
    return reads.run(
        () -> {
          var date = LocalDate.now(clock);
          var current = people.employee(actor, id, date, false);
          if (!actor.canRead(current)) throw new ChangeRequest.Denied();
          return history.history(actor.tenant(), id, date, current.startDate(), query);
        });
  }

  public Preview preview(Actor a, String id, String value) {
    return reads.run(
        () -> {
          var current = people.employee(a, id, LocalDate.now(clock), false);
          if (!a.canRead(current)) throw new ChangeRequest.Denied();
          var date = WorkCalendar.date(value, "date");
          var employee = people.employee(a, id, date, false);
          if (date.isBefore(employee.startDate()) || employee.status().equals("Ended"))
            throw new ChangeRequest.Conflict(
                "No employed calendar preview is available on this date.");
          var code = employee.calendarCode();
          if (code.isEmpty())
            throw new ChangeRequest.Conflict(
                "No approved calendar assignment is effective in this employment episode on this date.");
          var base =
              calendars
                  .find(a.tenant(), code)
                  .orElseThrow(
                      () -> new ChangeRequest.Conflict("Assigned calendar is unavailable."))
                  .calendar();
          if (date.isBefore(base.effectiveFrom())
              || date.isAfter(base.effectiveUntil())
              || retirements
                  .find(a.tenant(), code)
                  .filter(e -> e.retirement().applies(date))
                  .isPresent())
            throw new ChangeRequest.Conflict(
                "Assigned calendar is unavailable for this date. No day-off result is implied.");
          var selected =
              CalendarRevision.select(
                  base,
                  amendments.history(a.tenant(), base).stream().map(e -> e.revision()).toList(),
                  date);
          var classification = selected.policy().classification(date);
          return new Preview(
              id,
              base.name(),
              new CalendarModels.Preview(
                  code,
                  date,
                  base.timeZone(),
                  classification,
                  classification.equals("WORKING_DAY"),
                  selected.version(),
                  selected.effectiveDate()));
        });
  }

  public Review review(Actor a, String id) {
    if (!a.hr()) throw new ChangeRequest.Denied();
    return reads.run(
        () -> {
          var r = people.request(a, id, false, LocalDate.now(clock));
          if (!r.field().equals("calendarCode")) throw new ChangeRequest.Denied();
          if (r.newValue().isEmpty())
            return new Review(id, "", null, 0, false, false, null, List.of());
          var base =
              calendars
                  .find(a.tenant(), r.newValue())
                  .orElseThrow(ChangeRequest.Denied::new)
                  .calendar();
          var history = amendments.history(a.tenant(), base);
          var expected =
              people
                  .requestedCalendarVersion(a.tenant(), id)
                  .orElseThrow(
                      () -> new ChangeRequest.Conflict("Assignment policy snapshot is missing."));
          return new Review(
              id,
              r.newValue(),
              expected,
              history.size(),
              expected != history.size(),
              retirements.find(a.tenant(), r.newValue()).isPresent(),
              base,
              history.stream().map(e -> e.revision()).toList());
        });
  }
}
