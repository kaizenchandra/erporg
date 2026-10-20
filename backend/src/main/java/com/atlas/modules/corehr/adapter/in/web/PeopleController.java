package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.AuditEntry;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.model.DirectoryPage;
import com.atlas.modules.corehr.application.model.DirectoryQuery;
import com.atlas.modules.corehr.application.model.Notification;
import com.atlas.modules.corehr.application.model.PeopleSummary;
import com.atlas.modules.corehr.application.model.RequestPage;
import com.atlas.modules.corehr.application.model.RequestQuery;
import com.atlas.modules.corehr.application.port.in.PeopleUseCases;
import com.atlas.modules.corehr.domain.model.*;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PeopleController {
  private final PeopleUseCases people;

  public PeopleController(PeopleUseCases people) {
    this.people = people;
  }

  @GetMapping("/reporting/managers")
  com.atlas.modules.corehr.application.model.ManagerPage managers(
      Principal p,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "8") int size) {
    return people.managerCandidates(
        session(p),
        new com.atlas.modules.corehr.application.model.ManagerQuery(search, page, size));
  }

  @GetMapping("/directory")
  DirectoryPage directory(
      Principal p,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String department,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "8") int size,
      @RequestParam(defaultValue = "asc") String direction) {
    return people.directory(
        session(p), new DirectoryQuery(search, department, status, page, size, direction));
  }

  @GetMapping("/people-summary")
  PeopleSummary summary(Principal p) {
    return people.summary(session(p));
  }

  @GetMapping("/employees/{id}")
  Employee profile(Principal p, @PathVariable String id) {
    return people.profile(session(p), id);
  }

  @GetMapping("/request-inbox")
  RequestPage requestPage(
      Principal p,
      @RequestParam(defaultValue = "SUBMITTED") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "12") int size) {
    return people.requestPage(session(p), new RequestQuery(status, page, size));
  }

  @GetMapping("/session")
  Actor session(Principal p) {
    return people.actor(p.getName());
  }

  @GetMapping("/employees")
  List<Employee> employees(Principal p) {
    return people.employees(session(p));
  }

  @GetMapping("/requests")
  List<ChangeRequest> requests(Principal p) {
    return people.requests(session(p));
  }

  @PostMapping("/requests")
  ChangeRequest submit(
      Principal p,
      @RequestBody EmployeeChangeInput c,
      @RequestAttribute("correlationId") String correlation) {
    return people.submit(session(p), c.command(), correlation);
  }

  @PostMapping("/requests/{id}/withdraw")
  ChangeRequest withdraw(
      Principal p,
      @PathVariable String id,
      @RequestBody Withdraw c,
      @RequestAttribute("correlationId") String correlation) {
    return people.withdraw(session(p), id, c, correlation);
  }

  @PostMapping("/requests/{id}/decision")
  ChangeRequest decide(
      Principal p,
      @PathVariable String id,
      @RequestBody Decide c,
      @RequestAttribute("correlationId") String correlation) {
    return people.decide(session(p), id, c, correlation);
  }

  @GetMapping("/employees/{id}/history")
  List<AuditEntry> history(Principal p, @PathVariable String id) {
    return people.history(session(p), id);
  }

  @GetMapping("/notifications")
  List<Notification> notifications(Principal p) {
    return people.notifications(session(p));
  }
}
