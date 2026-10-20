package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.DepartmentParentModels.*;
import com.atlas.modules.corehr.application.port.in.DepartmentParentUseCases;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organisation/departments/{code}")
public final class DepartmentParentController {
  private final DepartmentParentUseCases parents;

  public DepartmentParentController(DepartmentParentUseCases parents) {
    this.parents = parents;
  }

  @GetMapping("/hierarchy")
  Context context(
      Principal principal,
      @PathVariable String code,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    return parents.context(principal.getName(), code, page, size);
  }

  @PostMapping("/parent-changes")
  Change correct(
      Principal principal,
      @PathVariable String code,
      @RequestBody Command command,
      @RequestAttribute("correlationId") String correlation) {
    return parents.correct(principal.getName(), code, command, correlation);
  }
}
