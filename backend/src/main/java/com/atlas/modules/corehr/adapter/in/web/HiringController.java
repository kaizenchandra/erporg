package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.Decide;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.domain.model.HireRequest;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/hiring/requests")
public final class HiringController {
  private final HiringUseCases hiring;
  private final PeopleUseCases people;

  public HiringController(HiringUseCases hiring, PeopleUseCases people) {
    this.hiring = hiring;
    this.people = people;
  }

  @GetMapping
  HirePage hires(
      Principal p,
      @RequestParam(defaultValue = "SUBMITTED") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "12") int size) {
    return hiring.hires(people.actor(p.getName()), new RequestQuery(status, page, size));
  }

  @PostMapping
  HireRequest submit(
      Principal p,
      @RequestBody SubmitHire command,
      @RequestAttribute("correlationId") String correlation) {
    return hiring.submit(people.actor(p.getName()), command, correlation);
  }

  @PostMapping("/{id}/decision")
  HireRequest decide(
      Principal p,
      @PathVariable String id,
      @RequestBody Decide command,
      @RequestAttribute("correlationId") String correlation) {
    return hiring.decide(people.actor(p.getName()), id, command, correlation);
  }

  @PostMapping("/{id}/complete-onboarding")
  OnboardingCompletion complete(
      Principal p,
      @PathVariable String id,
      @RequestBody OnboardingInput command,
      @RequestAttribute("correlationId") String correlation) {
    return hiring.completeOnboarding(people.actor(p.getName()), id, command.command(), correlation);
  }

  @GetMapping("/employees/{id}/employment-history")
  List<OnboardingCompletion> employmentHistory(Principal p, @PathVariable String id) {
    return hiring.employmentHistory(people.actor(p.getName()), id);
  }

  @GetMapping("/{id}/history")
  List<HireAudit> history(Principal p, @PathVariable String id) {
    return hiring.history(people.actor(p.getName()), id);
  }
}
