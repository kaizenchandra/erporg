package com.atlas.modules.timekeeping.adapter.in.web;

import com.atlas.modules.timekeeping.application.model.TimeModels.*;
import com.atlas.modules.timekeeping.application.port.in.TimeUseCases;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/time")
public final class TimeController {
  private final TimeUseCases time;

  public TimeController(TimeUseCases time) {
    this.time = time;
  }

  @GetMapping("/periods/{week}")
  PeriodContext period(Principal p, @PathVariable java.time.LocalDate week) {
    return time.period(p.getName(), week);
  }

  @PostMapping("/periods")
  Period configure(
      Principal p,
      @RequestBody SavePeriod c,
      @RequestAttribute("correlationId") String correlation) {
    return time.configurePeriod(p.getName(), c, correlation);
  }

  @GetMapping("/attendance/{employeeId}")
  Page<Shift> attendance(
      Principal p,
      @PathVariable String employeeId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    return time.attendance(p.getName(), employeeId, page, size);
  }

  @PostMapping("/punches/{action}")
  Shift punch(
      Principal p,
      @PathVariable String action,
      @RequestBody Punch c,
      @RequestAttribute("correlationId") String correlation) {
    return time.punch(p.getName(), action, c, correlation);
  }

  @PostMapping("/corrections")
  Shift correct(
      Principal p,
      @RequestBody Correction c,
      @RequestAttribute("correlationId") String correlation) {
    return time.correct(p.getName(), c, correlation);
  }

  @GetMapping("/sheets")
  Page<Sheet> sheets(
      Principal p,
      @RequestParam(defaultValue = "false") boolean review,
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    return time.sheets(p.getName(), review, status, page, size);
  }

  @GetMapping("/sheets/{id}")
  SheetContext sheet(Principal p, @PathVariable String id) {
    return time.sheet(p.getName(), id);
  }

  @PostMapping("/sheets/copy")
  Sheet copy(
      Principal p,
      @RequestBody CopySheet c,
      @RequestAttribute("correlationId") String correlation) {
    return time.copy(p.getName(), c, correlation);
  }

  @GetMapping("/dashboard")
  Dashboard dashboard(Principal p, @RequestParam java.time.LocalDate week) {
    return time.dashboard(p.getName(), week);
  }

  @GetMapping("/reports")
  Report report(
      Principal p,
      @RequestParam java.time.LocalDate from,
      @RequestParam java.time.LocalDate through,
      @RequestParam(defaultValue = "") String employeeId,
      @RequestParam(defaultValue = "ALL") String status) {
    return time.report(p.getName(), new ReportQuery(from, through, employeeId, status));
  }

  @PostMapping("/reports/export")
  ReportDownload export(
      Principal p,
      @RequestBody ReportQuery q,
      @RequestAttribute("correlationId") String correlation) {
    var report = time.exportReport(p.getName(), q, correlation);
    return new ReportDownload(
        "atlas-time-report.csv", TimeReportCsv.encode(report), report.items().size());
  }

  @PostMapping("/sheets")
  Sheet save(
      Principal p,
      @RequestBody SaveSheet c,
      @RequestAttribute("correlationId") String correlation) {
    return time.save(p.getName(), c, correlation);
  }

  @PostMapping("/sheets/{id}/actions")
  Sheet decide(
      Principal p,
      @PathVariable String id,
      @RequestBody Decision c,
      @RequestAttribute("correlationId") String correlation) {
    return time.decide(p.getName(), id, c, correlation);
  }

  @PostMapping("/sheets/selected-actions")
  java.util.List<Sheet> decideSelected(
      Principal p,
      @RequestBody BulkDecision c,
      @RequestAttribute("correlationId") String correlation) {
    return time.decideSelected(p.getName(), c, correlation);
  }

  @GetMapping("/history/{employeeId}")
  Page<Audit> history(
      Principal p,
      @PathVariable String employeeId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    return time.history(p.getName(), employeeId, page, size);
  }
}
