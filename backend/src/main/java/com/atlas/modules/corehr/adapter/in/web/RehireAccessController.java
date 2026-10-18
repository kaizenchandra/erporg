package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.RehireAccessModels.*;
import com.atlas.modules.corehr.application.port.in.RehireAccessUseCases;
import com.atlas.modules.corehr.domain.model.RehireAccessRevocation;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/employees/{employee}/rehire/access")
public final class RehireAccessController {
  private final RehireAccessUseCases useCases;

  public RehireAccessController(RehireAccessUseCases useCases) {
    this.useCases = useCases;
  }

  @GetMapping
  Context context(Principal p, @PathVariable String employee) {
    return useCases.context(p.getName(), employee);
  }

  @PostMapping("/revoke")
  RehireAccessRevocation revoke(
      Principal p,
      @PathVariable String employee,
      @RequestBody Revoke c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.revoke(p.getName(), employee, c, correlation);
  }
}
