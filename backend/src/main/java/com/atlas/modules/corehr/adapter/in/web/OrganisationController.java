package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.domain.model.Department;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organisation")
public final class OrganisationController {
  private final OrganisationUseCases organisation;
  private final PeopleUseCases people;

  public OrganisationController(OrganisationUseCases organisation, PeopleUseCases people) {
    this.organisation = organisation;
    this.people = people;
  }

  @GetMapping("/departments")
  List<Department> departments(Principal principal) {
    return organisation.departments(people.actor(principal.getName()));
  }

  @PostMapping("/departments")
  Department create(
      Principal principal,
      @RequestBody CreateDepartment command,
      @RequestAttribute("correlationId") String correlation) {
    return organisation.create(people.actor(principal.getName()), command, correlation);
  }

  @GetMapping("/history")
  List<DepartmentAudit> history(Principal principal) {
    return organisation.history(people.actor(principal.getName()));
  }
}
