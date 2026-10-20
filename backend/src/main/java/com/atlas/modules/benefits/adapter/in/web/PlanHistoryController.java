package com.atlas.modules.benefits.adapter.in.web;

import com.atlas.modules.benefits.application.model.PlanHistoryModels.*;
import com.atlas.modules.benefits.application.port.in.PlanHistoryUseCases;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class PlanHistoryController {
  private final PlanHistoryUseCases history;

  public PlanHistoryController(PlanHistoryUseCases history) {
    this.history = history;
  }

  @GetMapping("/api/v1/benefits/creation-history")
  Page history(
      Principal p,
      @RequestParam(defaultValue = "") String code,
      @RequestParam(defaultValue = "desc") String order,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String throughId) {
    return history.history(p.getName(), new Query(code, order, page, size, throughId));
  }
}
