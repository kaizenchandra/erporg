package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.DepartmentDirectoryModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class DepartmentDirectoryController {
  private final PeopleUseCases people;
  private final DepartmentDirectoryUseCases directory;

  public DepartmentDirectoryController(
      PeopleUseCases people, DepartmentDirectoryUseCases directory) {
    this.people = people;
    this.directory = directory;
  }

  @GetMapping("/api/v1/organisation/department-directory")
  Page browse(
      Principal principal,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "ALL") String timing,
      @RequestParam(defaultValue = "ALL") String relationship,
      @RequestParam(defaultValue = "") String parentCode,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "8") int size,
      @RequestParam(defaultValue = "asc") String direction) {
    return directory.browse(
        people.actor(principal.getName()),
        new Query(search, timing, relationship, parentCode, page, size, direction));
  }
}
