package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.ReferenceHistoryModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class ReferenceHistoryController {
  private final PeopleUseCases people;
  private final ReferenceHistoryUseCases history;

  public ReferenceHistoryController(PeopleUseCases people, ReferenceHistoryUseCases history) {
    this.people = people;
    this.history = history;
  }

  @GetMapping("/api/v1/organisation/reference-history")
  Page history(
      Principal p,
      @RequestParam String kind,
      @RequestParam(defaultValue = "") String code,
      @RequestParam(defaultValue = "desc") String order,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String throughId) {
    return history.history(
        people.actor(p.getName()), new Query(kind, code, order, page, size, throughId));
  }
}
