package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.EmploymentEndModels.*;
import com.atlas.modules.corehr.application.port.in.EmploymentEndUseCases;
import com.atlas.modules.corehr.domain.model.EmploymentEnd;
import com.atlas.modules.corehr.domain.model.EndWithdrawal;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/resignations/{id}/employment-end")
public final class EmploymentEndController {
  private final EmploymentEndUseCases useCases;

  public EmploymentEndController(EmploymentEndUseCases useCases) {
    this.useCases = useCases;
  }

  @GetMapping
  Context context(Principal p, @PathVariable String id) {
    return useCases.context(p.getName(), id);
  }

  @PostMapping
  EmploymentEnd confirm(
      Principal p,
      @PathVariable String id,
      @RequestBody Confirm c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.confirm(p.getName(), id, c, correlation);
  }

  @PostMapping("/withdrawals")
  EndWithdrawal withdraw(
      Principal p,
      @PathVariable String id,
      @RequestBody Confirm c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.withdraw(p.getName(), id, c, correlation);
  }

  @PostMapping("/withdrawals/{withdrawalId}/review")
  EndWithdrawal reviewWithdrawal(
      Principal p,
      @PathVariable String id,
      @PathVariable String withdrawalId,
      @RequestBody ReviewWithdrawal c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.reviewWithdrawal(p.getName(), id, withdrawalId, c, correlation);
  }
}
