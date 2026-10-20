package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.CalendarModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/company-calendars")
public final class CalendarsController {
  private final CalendarsUseCases calendars;
  private final PeopleUseCases people;

  public CalendarsController(CalendarsUseCases calendars, PeopleUseCases people) {
    this.calendars = calendars;
    this.people = people;
  }

  @GetMapping
  Page list(
      Principal p,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    return calendars.list(people.actor(p.getName()), new Query(search, status, page, size));
  }

  @GetMapping("/time-zones")
  List<String> zones(Principal p) {
    return calendars.timeZones(people.actor(p.getName()));
  }

  @GetMapping("/{code}")
  Entry detail(Principal p, @PathVariable String code) {
    return calendars.detail(people.actor(p.getName()), code);
  }

  @GetMapping("/{code}/preview")
  Preview preview(Principal p, @PathVariable String code, @RequestParam String date) {
    return calendars.preview(people.actor(p.getName()), code, date);
  }

  @PostMapping
  Entry create(
      Principal p, @RequestBody Create c, @RequestAttribute("correlationId") String correlation) {
    return calendars.create(people.actor(p.getName()), c, correlation);
  }
}
