package com.atlas.modules.benefits.adapter.in.web;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.in.SelectionInboxUseCases;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class SelectionInboxController {
  private final SelectionInboxUseCases inbox;

  public SelectionInboxController(SelectionInboxUseCases inbox) {
    this.inbox = inbox;
  }

  @GetMapping("/api/v1/benefits/selection-inbox")
  SelectionPage inbox(
      Principal p,
      @RequestParam(defaultValue = "OWN") String scope,
      @RequestParam(defaultValue = "PENDING") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "8") int size) {
    return inbox.inbox(p.getName(), new SelectionInboxQuery(scope, status, page, size));
  }
}
