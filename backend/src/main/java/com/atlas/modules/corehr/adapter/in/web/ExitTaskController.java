package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.ExitTaskModels.*;
import com.atlas.modules.corehr.application.port.in.ExitTaskUseCases;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class ExitTaskController {
  private final ExitTaskUseCases tasks;

  public ExitTaskController(ExitTaskUseCases tasks) {
    this.tasks = tasks;
  }

  @GetMapping("/exit-tasks")
  Page mine(
      Principal p,
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "12") int size) {
    return tasks.mine(p.getName(), new Query(status, page, size));
  }

  @GetMapping("/resignations/{id}/tasks")
  Case forCase(Principal p, @PathVariable String id) {
    return tasks.forCase(p.getName(), id);
  }

  @PostMapping("/resignations/{id}/tasks")
  Item create(
      Principal p,
      @PathVariable String id,
      @RequestBody Create c,
      @RequestAttribute("correlationId") String correlation) {
    return tasks.create(p.getName(), id, c, correlation);
  }

  @PostMapping("/exit-tasks/{id}/complete")
  Item complete(
      Principal p,
      @PathVariable String id,
      @RequestBody Complete c,
      @RequestAttribute("correlationId") String correlation) {
    return tasks.complete(p.getName(), id, c, correlation);
  }

  @GetMapping("/exit-tasks/{id}/history")
  List<Audit> history(Principal p, @PathVariable String id) {
    return tasks.history(p.getName(), id);
  }

  @PostMapping("/exit-tasks/{id}/management")
  Management manage(
      Principal p,
      @PathVariable String id,
      @RequestBody Manage c,
      @RequestAttribute("correlationId") String correlation) {
    return tasks.manage(p.getName(), id, c, correlation);
  }

  @GetMapping("/exit-tasks/{id}/management-history")
  List<Management> managementHistory(Principal p, @PathVariable String id) {
    return tasks.managementHistory(p.getName(), id);
  }
}
