package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.LegalEntityRetirementModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.domain.model.LegalEntityRetirement.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/legal-entities/{code}/retirement")
public final class LegalEntityRetirementController {
  private final LegalEntityRetirementUseCases retirement;
  private final PeopleUseCases people;

  public LegalEntityRetirementController(
      LegalEntityRetirementUseCases retirement, PeopleUseCases people) {
    this.retirement = retirement;
    this.people = people;
  }

  @GetMapping
  Context context(Principal p, @PathVariable String code) {
    return retirement.context(people.actor(p.getName()), code);
  }

  @PostMapping("/requests")
  Proposal submit(
      Principal p,
      @PathVariable String code,
      @RequestBody Submit c,
      @RequestAttribute("correlationId") String correlation) {
    return retirement.submit(people.actor(p.getName()), code, c, correlation);
  }

  @PostMapping("/requests/{id}/decision")
  Decision decide(
      Principal p,
      @PathVariable String code,
      @PathVariable String id,
      @RequestBody Review c,
      @RequestAttribute("correlationId") String correlation) {
    return retirement.decide(people.actor(p.getName()), code, id, c, correlation);
  }
}
