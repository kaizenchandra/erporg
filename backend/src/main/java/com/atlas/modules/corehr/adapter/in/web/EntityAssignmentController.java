package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.EntityAssignmentContext;
import com.atlas.modules.corehr.application.port.in.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class EntityAssignmentController {
  private final PeopleUseCases people;
  private final EntityAssignmentUseCases assignments;

  public EntityAssignmentController(PeopleUseCases people, EntityAssignmentUseCases assignments) {
    this.people = people;
    this.assignments = assignments;
  }

  @GetMapping("/api/v1/requests/{id}/entity-context")
  EntityAssignmentContext context(Principal p, @PathVariable String id) {
    return assignments.context(people.actor(p.getName()), id);
  }
}
