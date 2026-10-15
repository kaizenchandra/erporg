package com.atlas.modules.documents.adapter.in.web;

import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.in.DocumentMaintenanceUseCases;
import java.security.Principal;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local | oidc-demo")
@RequestMapping("/api/v1/document-templates/{code}")
public final class DocumentMaintenanceController {
  private final DocumentMaintenanceUseCases maintenance;

  public DocumentMaintenanceController(DocumentMaintenanceUseCases maintenance) {
    this.maintenance = maintenance;
  }

  @GetMapping("/revisions")
  List<DocumentTemplate> revisions(Principal p, @PathVariable String code) {
    return maintenance.revisions(p.getName(), code);
  }

  @PostMapping("/revisions")
  DocumentTemplate revise(
      Principal p,
      @PathVariable String code,
      @RequestBody ReviseDocumentTemplate input,
      @RequestAttribute("correlationId") String correlation) {
    return maintenance.revise(p.getName(), code, input, correlation);
  }

  @GetMapping("/evidence-history")
  List<DocumentEvidence> classifications(
      Principal p, @PathVariable String code, @RequestParam(required = false) String employeeId) {
    return maintenance.classifications(p.getName(), code, employeeId);
  }

  @PostMapping("/reclassifications")
  DocumentEvidence reclassify(
      Principal p,
      @PathVariable String code,
      @RequestBody ReclassifyDocument input,
      @RequestAttribute("correlationId") String correlation) {
    return maintenance.reclassify(p.getName(), code, input, correlation);
  }
}
