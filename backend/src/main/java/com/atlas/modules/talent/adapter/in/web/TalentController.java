package com.atlas.modules.talent.adapter.in.web;

import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.application.port.in.TalentUseCases;
import com.atlas.modules.talent.domain.model.TalentModels.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/talent")
public final class TalentController {
  private final TalentUseCases p;

  public TalentController(TalentUseCases p) {
    this.p = p;
  }

  @GetMapping("/context")
  Context context(
      Principal a,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "0") int page) {
    return p.context(a.getName(), search, page);
  }

  @GetMapping("/{kind}")
  Object page(
      Principal a,
      @PathVariable String kind,
      @RequestParam(defaultValue = "") String employee,
      @RequestParam(defaultValue = "0") int page) {
    return p.page(a.getName(), kind, employee, page);
  }

  @GetMapping("/{kind}/{id}")
  Object record(Principal a, @PathVariable String kind, @PathVariable String id) {
    return p.record(a.getName(), kind, id);
  }

  @GetMapping("/{kind}/{id}/history")
  Object history(
      Principal a,
      @PathVariable String kind,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page) {
    return p.history(a.getName(), kind, id, page);
  }

  @PostMapping("/requisitions")
  Requisition requisition(
      Principal a,
      @RequestBody RequisitionInput c,
      @RequestAttribute("correlationId") String corr) {
    return p.requisition(a.getName(), c, corr);
  }

  @PostMapping("/requisitions/{id}/actions")
  Requisition requisitionAction(
      Principal a,
      @PathVariable String id,
      @RequestBody Decision c,
      @RequestAttribute("correlationId") String corr) {
    return p.requisitionAction(a.getName(), id, c, corr);
  }

  @PostMapping("/requisitions/{id}/candidates")
  Requisition candidate(
      Principal a,
      @PathVariable String id,
      @RequestBody CandidateInput c,
      @RequestAttribute("correlationId") String corr) {
    return p.candidate(a.getName(), id, c, corr);
  }

  @PostMapping("/onboarding/{id}/actions")
  Onboarding joining(
      Principal a,
      @PathVariable String id,
      @RequestBody Joining c,
      @RequestAttribute("correlationId") String corr) {
    return p.joining(a.getName(), id, c, corr);
  }

  @PostMapping("/reviews")
  Review review(
      Principal a, @RequestBody ReviewInput c, @RequestAttribute("correlationId") String corr) {
    return p.review(a.getName(), c, corr);
  }

  @PostMapping("/reviews/{id}/actions")
  Review reviewAction(
      Principal a,
      @PathVariable String id,
      @RequestBody ReviewAction c,
      @RequestAttribute("correlationId") String corr) {
    return p.reviewAction(a.getName(), id, c, corr);
  }

  @PostMapping("/courses")
  Course course(
      Principal a, @RequestBody CourseInput c, @RequestAttribute("correlationId") String corr) {
    return p.course(a.getName(), c, corr);
  }

  @PostMapping("/learning")
  Enrollment assign(
      Principal a, @RequestBody Assignment c, @RequestAttribute("correlationId") String corr) {
    return p.assign(a.getName(), c, corr);
  }

  @PostMapping("/learning/{id}/actions")
  Enrollment learning(
      Principal a,
      @PathVariable String id,
      @RequestBody LearningAction c,
      @RequestAttribute("correlationId") String corr) {
    return p.learning(a.getName(), id, c, corr);
  }

  @PostMapping("/compensation")
  Compensation compensation(
      Principal a,
      @RequestBody CompensationInput c,
      @RequestAttribute("correlationId") String corr) {
    return p.compensation(a.getName(), c, corr);
  }

  @PostMapping("/compensation/{id}/actions")
  Compensation compensationAction(
      Principal a,
      @PathVariable String id,
      @RequestBody Decision c,
      @RequestAttribute("correlationId") String corr) {
    return p.compensationAction(a.getName(), id, c, corr);
  }

  @PostMapping("/succession")
  Succession succession(
      Principal a, @RequestBody SuccessionInput c, @RequestAttribute("correlationId") String corr) {
    return p.succession(a.getName(), c, corr);
  }

  @PostMapping("/succession/{id}/assessments")
  Succession successionUpdate(
      Principal a,
      @PathVariable String id,
      @RequestBody SuccessionUpdate c,
      @RequestAttribute("correlationId") String corr) {
    return p.successionUpdate(a.getName(), id, c, corr);
  }

  @PostMapping("/scenarios")
  Scenario scenario(
      Principal a, @RequestBody ScenarioInput c, @RequestAttribute("correlationId") String corr) {
    return p.scenario(a.getName(), c, corr);
  }
}
