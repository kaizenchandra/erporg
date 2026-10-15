package com.atlas.modules.documents.adapter.in.web;

import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.in.DocumentRetentionUseCases;
import java.security.Principal;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local | oidc-demo")
@RequestMapping("/api/v1/documents/{id}/retention")
public final class DocumentRetentionController {
  private final DocumentRetentionUseCases retention;

  public DocumentRetentionController(DocumentRetentionUseCases retention) {
    this.retention = retention;
  }

  @GetMapping
  RetentionReview review(Principal p, @PathVariable String id) {
    return retention.review(p.getName(), id);
  }

  @PostMapping
  RetentionEvent command(
      Principal p,
      @PathVariable String id,
      @RequestBody RetentionCommand c,
      @RequestAttribute("correlationId") String correlation) {
    return retention.command(p.getName(), id, c, correlation);
  }
}
