package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.LegalEntityRetirementModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reference-retirement-inbox")
public final class ReferenceRetirementInboxController {
  private final LegalEntityRetirementUseCases retirement;
  private final PeopleUseCases people;

  public ReferenceRetirementInboxController(
      LegalEntityRetirementUseCases retirement, PeopleUseCases people) {
    this.retirement = retirement;
    this.people = people;
  }

  @GetMapping
  InboxPage list(
      Principal principal,
      @RequestParam(defaultValue = "REVIEW") String scope,
      @RequestParam(defaultValue = "PENDING") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "8") int size) {
    return retirement.inbox(
        people.actor(principal.getName()), new InboxQuery(scope, status, page, size));
  }
}
