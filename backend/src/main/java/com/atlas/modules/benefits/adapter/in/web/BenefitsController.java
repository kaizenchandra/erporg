package com.atlas.modules.benefits.adapter.in.web;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.in.BenefitsUseCases;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/benefits")
public final class BenefitsController {
  private final BenefitsUseCases benefits;

  public BenefitsController(BenefitsUseCases benefits) {
    this.benefits = benefits;
  }

  @GetMapping("/plans")
  PlanPage plans(
      Principal p,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "ALL") String eligibility,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "8") int size,
      @RequestParam(defaultValue = "asc") String direction) {
    return benefits.plans(p.getName(), new PlanQuery(search, eligibility, page, size, direction));
  }

  @PostMapping("/plans")
  PlanView create(
      Principal p,
      @RequestBody CreatePlan command,
      @RequestAttribute("correlationId") String correlation) {
    return benefits.create(p.getName(), command, correlation);
  }

  @GetMapping("/history")
  List<PlanAudit> history(Principal p) {
    return benefits.history(p.getName());
  }
}
