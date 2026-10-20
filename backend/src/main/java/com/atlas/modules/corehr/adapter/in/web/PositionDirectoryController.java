package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.PositionDirectoryModels.*;
import com.atlas.modules.corehr.application.port.in.PositionDirectoryUseCases;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class PositionDirectoryController {
  private final PositionDirectoryUseCases directory;

  public PositionDirectoryController(PositionDirectoryUseCases directory) {
    this.directory = directory;
  }

  @GetMapping("/api/v1/organisation/position-directory")
  Page browse(
      Principal principal,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String departmentCode,
      @RequestParam(defaultValue = "ALL") String timing,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "8") int size,
      @RequestParam(defaultValue = "asc") String direction) {
    return directory.browse(
        principal.getName(), new Query(search, departmentCode, timing, page, size, direction));
  }
}
