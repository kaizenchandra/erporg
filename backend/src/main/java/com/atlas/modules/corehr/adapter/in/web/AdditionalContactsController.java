package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.ContactModels.*;
import com.atlas.modules.corehr.application.port.in.AdditionalContactsUseCases;
import com.atlas.modules.corehr.domain.model.ContactRequest;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/employees/{employeeId}/additional-contacts")
public final class AdditionalContactsController {
  private final AdditionalContactsUseCases contacts;

  public AdditionalContactsController(AdditionalContactsUseCases contacts) {
    this.contacts = contacts;
  }

  @GetMapping
  Context context(
      Principal p,
      @PathVariable String employeeId,
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "8") int size) {
    return contacts.context(p.getName(), employeeId, new Query(status, page, size));
  }

  @PostMapping("/requests")
  ContactRequest submit(
      Principal p,
      @PathVariable String employeeId,
      @RequestBody Submit command,
      @RequestAttribute("correlationId") String correlation) {
    return contacts.submit(p.getName(), employeeId, command, correlation);
  }

  @PostMapping("/requests/{id}/decision")
  ContactRequest review(
      Principal p,
      @PathVariable String employeeId,
      @PathVariable String id,
      @RequestBody Review command,
      @RequestAttribute("correlationId") String correlation) {
    return contacts.review(p.getName(), employeeId, id, command, correlation);
  }
}
