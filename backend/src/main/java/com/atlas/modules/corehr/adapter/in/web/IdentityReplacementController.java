package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.IdentityReplacementModels.*;
import com.atlas.modules.corehr.application.port.in.IdentityReplacementUseCases;
import com.atlas.modules.corehr.domain.model.IdentityReplacement;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/employees/{employee}/rehire/access/replacement")
public final class IdentityReplacementController {
  private final IdentityReplacementUseCases useCases;

  public IdentityReplacementController(IdentityReplacementUseCases useCases) {
    this.useCases = useCases;
  }

  @GetMapping
  Context context(Principal p, @PathVariable String employee) {
    return useCases.context(p.getName(), employee);
  }

  @PostMapping
  IdentityReplacement submit(
      Principal p,
      @PathVariable String employee,
      @RequestBody Submit c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.submit(p.getName(), employee, c, correlation);
  }

  @PostMapping("/{id}/review")
  IdentityReplacement review(
      Principal p,
      @PathVariable String employee,
      @PathVariable String id,
      @RequestBody Review c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.review(p.getName(), employee, id, c, correlation);
  }
}
