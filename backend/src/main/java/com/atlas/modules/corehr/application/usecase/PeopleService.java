package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.AuditEntry;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.model.DirectoryPage;
import com.atlas.modules.corehr.application.model.DirectoryQuery;
import com.atlas.modules.corehr.application.model.Notification;
import com.atlas.modules.corehr.application.model.PeopleReadScope;
import com.atlas.modules.corehr.application.model.PeopleSummary;
import com.atlas.modules.corehr.application.model.RequestPage;
import com.atlas.modules.corehr.application.model.RequestQuery;
import com.atlas.modules.corehr.application.port.in.OrganisationUseCases;
import com.atlas.modules.corehr.application.port.in.PeopleUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;

public final class PeopleService implements PeopleUseCases {
  private final PeopleStore store;
  private final UnitOfWork transactions;
  private final Clock clock;
  private final OrganisationUseCases organisation;

  public PeopleService(
      PeopleStore store, UnitOfWork transactions, Clock clock, OrganisationUseCases organisation) {
    this.store = store;
    this.transactions = transactions;
    this.clock = clock;
    this.organisation = organisation;
  }

  public DirectoryPage directory(Actor actor, DirectoryQuery query) {
    var page = store.directory(readScope(actor), query, LocalDate.now(clock));
    return new DirectoryPage(
        page.items().stream().map(e -> visibleEmployee(actor, e)).toList(),
        page.total(),
        page.page(),
        page.size(),
        page.departments());
  }

  public PeopleSummary summary(Actor actor) {
    return store.summary(readScope(actor));
  }

  public Employee profile(Actor actor, String id) {
    var employee = store.employee(actor, id, LocalDate.now(clock), false);
    if (!actor.canRead(employee)) throw new ChangeRequest.Denied();
    return visibleEmployee(actor, employee);
  }

  public RequestPage requestPage(Actor actor, RequestQuery query) {
    return store.requestPage(readScope(actor), query);
  }

  public Actor actor(String subject) {
    return store.actor(subject);
  }

  public List<Employee> employees(Actor actor) {
    return store.employees(readScope(actor), LocalDate.now(clock)).stream()
        .map(e -> visibleEmployee(actor, e))
        .toList();
  }

  public List<ChangeRequest> requests(Actor actor) {
    return store.requests(readScope(actor));
  }

  private Employee visibleEmployee(Actor actor, Employee e) {
    if (actor.canReadField(e, "address")) return e;
    return new Employee(
        e.id(),
        e.name(),
        e.email(),
        e.phone(),
        null,
        null,
        null,
        null,
        e.title(),
        e.department(),
        e.location(),
        e.status(),
        e.managerId(),
        e.startDate(),
        e.version());
  }

  private PeopleReadScope readScope(Actor actor) {
    var fields =
        ChangeRequest.FIELDS.stream()
            .filter(f -> actor.hr() || !ChangeRequest.PRIVATE_FIELDS.contains(f))
            .sorted()
            .toList();
    return switch (actor.role()) {
      case "HR" ->
          new PeopleReadScope(
              actor.tenant(), actor.subject(), actor.employeeId(), true, false, fields);
      case "MANAGER" ->
          new PeopleReadScope(
              actor.tenant(), actor.subject(), actor.employeeId(), false, true, fields);
      case "EMPLOYEE" ->
          new PeopleReadScope(
              actor.tenant(), actor.subject(), actor.employeeId(), false, false, fields);
      default -> throw new ChangeRequest.Denied();
    };
  }

  public ChangeRequest submit(Actor actor, Submit c, String correlation) {
    if (c.employeeId() == null
        || c.employeeId().isBlank()
        || c.employeeId().length() > 40
        || c.version() < 0)
      throw new IllegalArgumentException("A valid employee identifier and version are required.");
    ChangeRequest.validate(
        c.field(), c.newValue(), c.reason(), c.effectiveDate(), LocalDate.now(clock));
    if (c.idempotencyKey() == null || !c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException("A valid idempotency key is required.");
    return transactions.run(
        () -> {
          Employee e = store.employee(actor, c.employeeId(), LocalDate.now(clock), true);
          if (!actor.canChange(e, c.field())) throw new ChangeRequest.Denied();
          var previous = store.duplicate(actor, c.idempotencyKey());
          if (previous.isPresent()) {
            var p = previous.get();
            if (!p.employeeId().equals(c.employeeId())
                || !p.field().equals(c.field())
                || !p.newValue().equals(c.newValue().trim())
                || !p.reason().equals(c.reason().trim())
                || !p.effectiveDate().equals(c.effectiveDate())
                || p.employeeVersion() != c.version())
              throw new ChangeRequest.Conflict(
                  "Idempotency key was already used for different input.");
            return p;
          }
          if (e.version() != c.version())
            throw new ChangeRequest.Conflict(
                "This profile has changed. Refresh before submitting.");
          if (c.field().equals("department"))
            organisation.requireDepartment(actor, c.newValue().trim(), c.effectiveDate());
          String old =
              switch (c.field()) {
                case "phone" -> e.phone();
                case "title" -> e.title();
                case "department" -> e.department();
                case "location" -> e.location();
                case "address" -> e.address();
                case "emergencyContactName" -> e.emergencyContactName();
                case "emergencyContactRelationship" -> e.emergencyContactRelationship();
                case "emergencyContactPhone" -> e.emergencyContactPhone();
                default -> throw new IllegalArgumentException("Unsupported field.");
              };
          if (old.equals(c.newValue().trim()))
            throw new IllegalArgumentException("The new value must differ from the current value.");
          var request =
              new ChangeRequest(
                  UUID.randomUUID().toString(),
                  e.id(),
                  e.name(),
                  c.field(),
                  old,
                  c.newValue().trim(),
                  c.effectiveDate(),
                  c.reason().trim(),
                  "SUBMITTED",
                  actor.subject(),
                  e.version(),
                  clock.instant(),
                  null,
                  null);
          store.insert(actor, request, c.idempotencyKey(), correlation);
          return request;
        });
  }

  public ChangeRequest decide(Actor actor, String id, Decide c, String correlation) {
    if (c.comment() == null || c.comment().isBlank() || c.comment().length() > 500)
      throw new IllegalArgumentException("A decision comment of 1–500 characters is required.");
    return transactions.run(
        () -> {
          var r = store.request(actor, id, true);
          var e = store.employee(actor, r.employeeId(), LocalDate.now(clock), true);
          r.ensureDecidable(actor, e, c.decision());
          if (c.decision().equals("APPROVED") && store.hasLaterChange(actor, r))
            throw new ChangeRequest.Conflict(
                "A later effective change exists. Resolve it before approving this request.");
          if (c.decision().equals("APPROVED") && r.field().equals("department"))
            organisation.requireDepartment(actor, r.newValue(), r.effectiveDate());
          store.decide(actor, r, c.decision(), c.comment().trim(), clock.instant(), correlation);
          return store.request(actor, id, false);
        });
  }

  public List<AuditEntry> history(Actor actor, String id) {
    var e = store.employee(actor, id, LocalDate.now(clock), false);
    if (!actor.canRead(e)) throw new ChangeRequest.Denied();
    return store.history(actor, id).stream()
        .filter(entry -> actor.canReadField(e, entry.field()))
        .toList();
  }

  public List<Notification> notifications(Actor actor) {
    return store.notifications(actor);
  }
}
