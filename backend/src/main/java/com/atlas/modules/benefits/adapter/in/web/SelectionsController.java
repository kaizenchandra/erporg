package com.atlas.modules.benefits.adapter.in.web;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.in.SelectionsUseCases;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/benefits/selections")
public final class SelectionsController {
  private final SelectionsUseCases selections;

  public SelectionsController(SelectionsUseCases selections) {
    this.selections = selections;
  }

  @GetMapping
  SelectionPage list(
      Principal p,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "8") int size) {
    return selections.list(p.getName(), page, size);
  }

  @PostMapping
  SelectionView submit(
      Principal p,
      @RequestBody SubmitSelection input,
      @RequestAttribute("correlationId") String correlation) {
    return selections.submit(p.getName(), input, correlation);
  }

  @PostMapping("/{id}/withdrawal")
  SelectionView withdraw(
      Principal p,
      @PathVariable String id,
      @RequestBody WithdrawSelection input,
      @RequestAttribute("correlationId") String correlation) {
    return selections.withdraw(p.getName(), id, input, correlation);
  }

  @PostMapping("/{id}/decision")
  SelectionView decide(
      Principal p,
      @PathVariable String id,
      @RequestBody BenefitDecisionInput input,
      @RequestAttribute("correlationId") String correlation) {
    return selections.decide(p.getName(), id, input.command(), correlation);
  }
}
