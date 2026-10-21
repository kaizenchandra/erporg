package com.atlas.modules.payroll.adapter.in.web;

import com.atlas.modules.payroll.application.model.PayrollModels.*;
import com.atlas.modules.payroll.application.port.in.PayrollUseCases;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payroll")
public final class PayrollController {
  private final PayrollUseCases p;

  public PayrollController(PayrollUseCases p) {
    this.p = p;
  }

  @GetMapping("/grants")
  Object grants(Principal a) {
    return p.grants(a.getName());
  }

  @GetMapping("/runs")
  Page runs(Principal a, @RequestParam String entity, @RequestParam(defaultValue = "0") int page) {
    return p.runs(a.getName(), entity, page);
  }

  @GetMapping("/runs/{id}")
  Run run(Principal a, @PathVariable String id) {
    return p.run(a.getName(), id);
  }

  @PostMapping("/runs")
  Run prepare(
      Principal a, @RequestBody Prepare c, @RequestAttribute("correlationId") String correlation) {
    return p.prepare(a.getName(), c, correlation);
  }

  @PostMapping("/compensation-runs")
  Run prepareCompensation(
      Principal a, @RequestBody Prepare c, @RequestAttribute("correlationId") String correlation) {
    return p.prepareCompensation(a.getName(), c, correlation);
  }

  @PostMapping("/runs/{id}/actions")
  Run action(
      Principal a,
      @PathVariable String id,
      @RequestBody Action c,
      @RequestAttribute("correlationId") String correlation) {
    return p.action(a.getName(), id, c, correlation);
  }

  @GetMapping("/runs/{id}/history")
  Object history(Principal a, @PathVariable String id) {
    return p.history(a.getName(), id);
  }

  @PostMapping("/runs/{id}/payslip")
  Document payslip(
      Principal a, @PathVariable String id, @RequestAttribute("correlationId") String correlation) {
    return p.payslip(a.getName(), id, correlation);
  }
}
