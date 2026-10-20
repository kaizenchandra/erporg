package com.atlas.modules.finance.adapter.in.web;

import com.atlas.modules.finance.application.model.FinanceModels.*;
import com.atlas.modules.finance.application.port.in.FinanceUseCases;
import java.security.Principal;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/finance")
public final class FinanceController {
  private final FinanceUseCases f;

  public FinanceController(FinanceUseCases f) {
    this.f = f;
  }

  @GetMapping("/context")
  Context context(Principal p) {
    return f.context(p.getName());
  }

  @PostMapping("/ledgers")
  Ledger setup(Principal p, @RequestBody Setup c, @RequestAttribute("correlationId") String id) {
    return f.setup(p.getName(), c, id);
  }

  @GetMapping("/journals")
  Page<Journal> journals(
      Principal p,
      @RequestParam String entity,
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "0") int page) {
    return f.journals(p.getName(), entity, status, page);
  }

  @GetMapping("/journals/{id}")
  Journal journal(Principal p, @PathVariable String id) {
    return f.journal(p.getName(), id);
  }

  @PostMapping("/journals")
  Journal save(
      Principal p, @RequestBody SaveJournal c, @RequestAttribute("correlationId") String id) {
    return f.save(p.getName(), c, id);
  }

  @PostMapping("/journals/{id}/actions")
  Journal action(
      Principal p,
      @PathVariable String id,
      @RequestBody Action c,
      @RequestAttribute("correlationId") String correlation) {
    return f.action(p.getName(), id, c, correlation);
  }

  @GetMapping("/period")
  PeriodContext period(Principal p, @RequestParam String entity, @RequestParam LocalDate month) {
    return new PeriodContext(f.period(p.getName(), entity, month));
  }

  @PostMapping("/periods")
  Period setPeriod(
      Principal p, @RequestBody SetPeriod c, @RequestAttribute("correlationId") String id) {
    return f.setPeriod(p.getName(), c, id);
  }

  @GetMapping("/trial-balance")
  TrialBalance balance(
      Principal p,
      @RequestParam String entity,
      @RequestParam LocalDate from,
      @RequestParam LocalDate through) {
    return f.trialBalance(p.getName(), entity, from, through);
  }

  @GetMapping("/history")
  Page<Audit> history(
      Principal p, @RequestParam String entity, @RequestParam(defaultValue = "0") int page) {
    return f.history(p.getName(), entity, page);
  }
}
