package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.PostalAddressModels.*;
import com.atlas.modules.corehr.application.port.in.PeopleUseCases;
import com.atlas.modules.corehr.domain.model.ChangeRequest;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/employees/{id}/postal-address")
public final class PostalAddressController {
  private final PeopleUseCases people;

  public PostalAddressController(PeopleUseCases people) {
    this.people = people;
  }

  @GetMapping
  Context context(Principal p, @PathVariable String id) {
    return people.postalAddress(people.actor(p.getName()), id);
  }

  @PostMapping
  ChangeRequest submit(
      Principal p,
      @PathVariable String id,
      @RequestBody PostalAddressInput c,
      @RequestAttribute("correlationId") String correlation) {
    return people.submitPostalAddress(people.actor(p.getName()), id, c.command(), correlation);
  }
}
