package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.CalendarAmendmentModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/company-calendars/{code}/amendments")
public final class CalendarAmendmentsController {
  private final CalendarAmendmentsUseCases calendars;
  private final PeopleUseCases people;

  public CalendarAmendmentsController(CalendarAmendmentsUseCases calendars, PeopleUseCases people) {
    this.calendars = calendars;
    this.people = people;
  }

  @GetMapping
  Context context(Principal p, @PathVariable String code) {
    return calendars.context(people.actor(p.getName()), code);
  }

  @PostMapping
  RevisionEntry amend(
      Principal p,
      @PathVariable String code,
      @RequestBody Amend c,
      @RequestAttribute("correlationId") String correlation) {
    return calendars.amend(people.actor(p.getName()), code, c, correlation);
  }
}
