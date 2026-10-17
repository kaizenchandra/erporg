package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.EndRescheduleModels.*;
import com.atlas.modules.corehr.application.port.in.EndRescheduleUseCases;
import com.atlas.modules.corehr.domain.model.EndReschedule;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/resignations/{id}/employment-end/rescheduling")
public final class EndRescheduleController {
  private final EndRescheduleUseCases useCases;

  public EndRescheduleController(EndRescheduleUseCases useCases) {
    this.useCases = useCases;
  }

  @GetMapping
  Context context(Principal p, @PathVariable String id) {
    return useCases.context(p.getName(), id);
  }

  @PostMapping
  EndReschedule submit(
      Principal p,
      @PathVariable String id,
      @RequestBody Submit c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.submit(p.getName(), id, c, correlation);
  }

  @PostMapping("/{rescheduleId}/review")
  EndReschedule review(
      Principal p,
      @PathVariable String id,
      @PathVariable String rescheduleId,
      @RequestBody Review c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.review(p.getName(), id, rescheduleId, c, correlation);
  }
}
