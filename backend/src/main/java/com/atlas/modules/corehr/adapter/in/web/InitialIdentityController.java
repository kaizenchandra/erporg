package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.InitialIdentityModels.*;
import com.atlas.modules.corehr.application.port.in.InitialIdentityUseCases;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/initial-identities")
public final class InitialIdentityController {
  private final InitialIdentityUseCases useCases;

  public InitialIdentityController(InitialIdentityUseCases u) {
    useCases = u;
  }

  @GetMapping("/employees/{employee}")
  Context context(Principal p, @PathVariable String employee) {
    return useCases.context(p.getName(), employee);
  }

  @PostMapping("/employees/{employee}")
  Binding submit(
      Principal p,
      @PathVariable String employee,
      @RequestBody Submit c,
      @RequestAttribute("correlationId") String corr) {
    return useCases.submit(p.getName(), employee, c, corr);
  }

  @PostMapping("/{id}/review")
  Binding review(
      Principal p,
      @PathVariable String id,
      @RequestBody Review c,
      @RequestAttribute("correlationId") String corr) {
    return useCases.review(p.getName(), id, c, corr);
  }
}
