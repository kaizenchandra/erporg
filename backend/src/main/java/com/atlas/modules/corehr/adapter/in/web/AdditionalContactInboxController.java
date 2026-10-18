package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.ContactModels.*;
import com.atlas.modules.corehr.application.port.in.AdditionalContactsUseCases;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/additional-contact-inbox")
public final class AdditionalContactInboxController {
  private final AdditionalContactsUseCases contacts;

  public AdditionalContactInboxController(AdditionalContactsUseCases contacts) {
    this.contacts = contacts;
  }

  @GetMapping
  Page inbox(
      Principal p,
      @RequestParam(defaultValue = "REVIEW") String scope,
      @RequestParam(defaultValue = "SUBMITTED") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "8") int size) {
    return contacts.inbox(p.getName(), scope, new Query(status, page, size));
  }
}
