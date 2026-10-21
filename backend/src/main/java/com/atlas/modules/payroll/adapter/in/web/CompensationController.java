package com.atlas.modules.payroll.adapter.in.web;

import com.atlas.modules.payroll.api.CompensationAccess.Salary;
import com.atlas.modules.payroll.application.model.CompensationModels.*;
import com.atlas.modules.payroll.application.port.in.CompensationUseCases;
import java.security.Principal;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payroll/compensation")
public final class CompensationController {
  private final CompensationUseCases useCases;

  public CompensationController(CompensationUseCases u) {
    useCases = u;
  }

  @GetMapping("/employees/{employee}")
  State state(Principal p, @PathVariable String employee) {
    return useCases.state(p.getName(), employee);
  }

  @GetMapping("/employees/{employee}/salary")
  Salary salary(Principal p, @PathVariable String employee, @RequestParam LocalDate date) {
    return useCases.salary(p.getName(), employee, date);
  }

  @PostMapping("/baselines")
  Setup propose(
      Principal p, @RequestBody Baseline c, @RequestAttribute("correlationId") String corr) {
    return useCases.propose(p.getName(), c, corr);
  }

  @PostMapping("/baselines/{id}/decisions")
  Setup decide(
      Principal p,
      @PathVariable String id,
      @RequestBody Decision c,
      @RequestAttribute("correlationId") String corr) {
    return useCases.decide(p.getName(), id, c, corr);
  }

  @GetMapping("/calculations/{id}")
  Calculation calculation(Principal p, @PathVariable String id) {
    return useCases.calculation(p.getName(), id);
  }
}
