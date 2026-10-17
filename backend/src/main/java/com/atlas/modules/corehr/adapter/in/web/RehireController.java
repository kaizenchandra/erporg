package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.RehireModels.*;
import com.atlas.modules.corehr.application.port.in.RehireUseCases;
import com.atlas.modules.corehr.domain.model.Rehire;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/employees/{employee}/rehire")
public final class RehireController {
  private final RehireUseCases useCases;

  public RehireController(RehireUseCases useCases) {
    this.useCases = useCases;
  }

  @GetMapping
  Context context(Principal p, @PathVariable String employee) {
    return useCases.context(p.getName(), employee);
  }

  @PostMapping
  Rehire submit(
      Principal p,
      @PathVariable String employee,
      @RequestBody Submit c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.submit(p.getName(), employee, c, correlation);
  }

  @PostMapping("/{id}/review")
  Rehire review(
      Principal p,
      @PathVariable String employee,
      @PathVariable String id,
      @RequestBody Review c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.review(p.getName(), employee, id, c, correlation);
  }
}
