package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.CalendarRetirementModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/company-calendars/{code}/retirement")
public final class CalendarRetirementController {
  private final CalendarRetirementUseCases retirements;
  private final PeopleUseCases people;

  public CalendarRetirementController(
      CalendarRetirementUseCases retirements, PeopleUseCases people) {
    this.retirements = retirements;
    this.people = people;
  }

  @PostMapping
  Entry retire(
      Principal p,
      @PathVariable String code,
      @RequestBody Retire c,
      @RequestAttribute("correlationId") String correlation) {
    return retirements.retire(people.actor(p.getName()), code, c, correlation);
  }
}
