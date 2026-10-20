package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.LegalEntityModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/legal-entities")
public final class LegalEntitiesController {
  private final LegalEntitiesUseCases entities;
  private final PeopleUseCases people;

  public LegalEntitiesController(LegalEntitiesUseCases entities, PeopleUseCases people) {
    this.entities = entities;
    this.people = people;
  }

  @GetMapping
  Page list(
      Principal p,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    return entities.list(people.actor(p.getName()), new Query(search, status, page, size));
  }

  @GetMapping("/options")
  Options options(Principal p) {
    return entities.options(people.actor(p.getName()));
  }

  @PostMapping
  Entry create(
      Principal p, @RequestBody Create c, @RequestAttribute("correlationId") String correlation) {
    return entities.create(people.actor(p.getName()), c, correlation);
  }
}
