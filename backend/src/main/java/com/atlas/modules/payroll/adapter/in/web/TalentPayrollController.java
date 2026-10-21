package com.atlas.modules.payroll.adapter.in.web;

import com.atlas.modules.payroll.application.port.in.TalentIntakeUseCases;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payroll/talent-inputs")
public final class TalentPayrollController {
  private final TalentIntakeUseCases p;

  public TalentPayrollController(TalentIntakeUseCases p) {
    this.p = p;
  }

  public record Synchronize(String reason) {}

  @PostMapping("/{source}/synchronize")
  Object synchronize(
      Principal a,
      @PathVariable String source,
      @RequestBody Synchronize c,
      @RequestAttribute("correlationId") String corr) {
    return p.synchronize(a.getName(), source, c.reason(), corr);
  }

  @GetMapping
  Object pending(Principal a, @RequestParam(defaultValue = "0") int page) {
    return p.pending(a.getName(), page);
  }
}
