package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.EmployeeCalendarModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class EmployeeCalendarController {
  private final PeopleUseCases people;
  private final EmployeeCalendarUseCases calendars;

  public EmployeeCalendarController(PeopleUseCases people, EmployeeCalendarUseCases calendars) {
    this.people = people;
    this.calendars = calendars;
  }

  @GetMapping("/employees/{id}/calendar-history")
  History history(
      Principal p,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "8") int size) {
    return calendars.history(people.actor(p.getName()), id, new HistoryQuery(page, size));
  }

  @GetMapping("/employees/{id}/calendar-preview")
  Preview preview(Principal p, @PathVariable String id, @RequestParam String date) {
    return calendars.preview(people.actor(p.getName()), id, date);
  }

  @GetMapping("/requests/{id}/calendar-context")
  Review review(Principal p, @PathVariable String id) {
    return calendars.review(people.actor(p.getName()), id);
  }
}
