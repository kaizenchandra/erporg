package com.atlas.modules.documents.adapter.in.web;

import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.in.DocumentTemplatesUseCases;
import java.security.Principal;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local | oidc-demo")
@RequestMapping("/api/v1/document-templates")
public final class DocumentTemplatesController {
  private final DocumentTemplatesUseCases templates;

  public DocumentTemplatesController(DocumentTemplatesUseCases templates) {
    this.templates = templates;
  }

  @GetMapping
  List<DocumentTemplate> list(Principal p) {
    return templates.templates(p.getName());
  }

  @PostMapping
  DocumentTemplate create(
      Principal p,
      @RequestBody CreateDocumentTemplate c,
      @RequestAttribute("correlationId") String correlation) {
    return templates.create(p.getName(), c, correlation);
  }

  @PostMapping("/{code}/evidence")
  DocumentEvidence link(
      Principal p,
      @PathVariable String code,
      @RequestBody LinkDocumentEvidence c,
      @RequestAttribute("correlationId") String correlation) {
    return templates.link(p.getName(), code, c, correlation);
  }

  @GetMapping("/completeness")
  DocumentCompleteness completeness(
      Principal p, @RequestParam(required = false) String employeeId) {
    return templates.completeness(p.getName(), employeeId);
  }

  @GetMapping("/evidence")
  List<DocumentEvidence> evidence(Principal p, @RequestParam(required = false) String employeeId) {
    return templates.history(p.getName(), employeeId);
  }
}
