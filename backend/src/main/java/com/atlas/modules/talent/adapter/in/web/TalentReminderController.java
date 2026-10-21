package com.atlas.modules.talent.adapter.in.web;

import com.atlas.modules.talent.application.model.ReminderModels.*;
import com.atlas.modules.talent.application.port.in.ReminderUseCases;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/talent/reminders")
public final class TalentReminderController {
  private final ReminderUseCases jobs;

  public TalentReminderController(ReminderUseCases j) {
    jobs = j;
  }

  @GetMapping
  Context context(Principal p, @RequestParam(defaultValue = "0") int page) {
    return jobs.context(p.getName(), page);
  }

  @PostMapping("/policy")
  Policy policy(
      Principal p, @RequestBody Configure c, @RequestAttribute("correlationId") String corr) {
    return jobs.configure(p.getName(), c, corr);
  }

  @PostMapping("/run")
  Run run(Principal p, @RequestBody Command c, @RequestAttribute("correlationId") String corr) {
    return jobs.run(p.getName(), c, corr);
  }

  @PostMapping("/retry")
  State retry(Principal p, @RequestBody Command c, @RequestAttribute("correlationId") String corr) {
    return jobs.retry(p.getName(), c, corr);
  }

  @GetMapping("/runs/{id}/items")
  Object items(Principal p, @PathVariable String id) {
    return jobs.items(p.getName(), id);
  }
}
