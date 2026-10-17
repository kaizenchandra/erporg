package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.RehireActivationModels.*;
import com.atlas.modules.corehr.application.port.in.RehireActivationUseCases;
import com.atlas.modules.corehr.domain.model.RehireActivation;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/employees/{employee}/rehire/activation")
public final class RehireActivationController {
  private final RehireActivationUseCases useCases;

  public RehireActivationController(RehireActivationUseCases useCases) {
    this.useCases = useCases;
  }

  @GetMapping
  Context context(Principal p, @PathVariable String employee) {
    return useCases.context(p.getName(), employee);
  }

  @PostMapping
  RehireActivation submit(
      Principal p,
      @PathVariable String employee,
      @RequestBody Submit c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.submit(p.getName(), employee, c, correlation);
  }

  @PostMapping("/{id}/review")
  RehireActivation review(
      Principal p,
      @PathVariable String employee,
      @PathVariable String id,
      @RequestBody Review c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.review(p.getName(), employee, id, c, correlation);
  }
}
