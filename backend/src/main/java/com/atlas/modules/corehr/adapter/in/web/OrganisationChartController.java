package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.port.in.OrganisationBrowseUseCases;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organisation/chart")
public final class OrganisationChartController {
  private final OrganisationBrowseUseCases browse;

  public OrganisationChartController(OrganisationBrowseUseCases browse) {
    this.browse = browse;
  }

  @GetMapping
  OrganisationBranch chart(
      Principal p,
      @RequestParam(defaultValue = "") String parentId,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "12") int size) {
    return browse.browse(p.getName(), new OrganisationChartQuery(parentId, search, page, size));
  }
}
