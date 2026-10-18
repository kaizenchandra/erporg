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

  private static final List<String> MANAGER_ROLES = List.of("HR", "MANAGER");

  public com.atlas.modules.corehr.application.model.ManagerPage managerCandidates(
      Actor actor, com.atlas.modules.corehr.application.model.ManagerQuery query) {
    if (!actor.hr() && !actor.role().equals("MANAGER")) throw new ChangeRequest.Denied();
    return store.managerCandidates(actor.tenant(), query, MANAGER_ROLES, LocalDate.now(clock));
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
    return store.summary(readScope(actor), LocalDate.now(clock));
  }

  public Employee profile(Actor actor, String id) {
    var employee = store.employee(actor, id, LocalDate.now(clock), false);
    if (!actor.canRead(employee)) throw new ChangeRequest.Denied();
    return visibleEmployee(actor, employee);
  }

  public RequestPage requestPage(Actor actor, RequestQuery query) {
    return store.requestPage(readScope(actor), query, LocalDate.now(clock));
  }

  public String sessionEpoch(String subject) {
    return store.sessionEpoch(subject, LocalDate.now(clock));
  }

  public Actor actor(String subject) {
    return store.actor(subject, LocalDate.now(clock));
  }

  public List<Employee> employees(Actor actor) {
    return store.employees(readScope(actor), LocalDate.now(clock)).stream()
        .map(e -> visibleEmployee(actor, e))
        .toList();
  }

  public List<ChangeRequest> requests(Actor actor) {
    return store.requests(readScope(actor), LocalDate.now(clock));
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
        e.positionCode(),
        null,
        e.version());
  }

  private PeopleReadScope readScope(Actor actor) {
    return PeopleReadScope.of(actor);
  }

  public ChangeRequest submit(Actor actor, Submit c, String correlation) {
    if (c.employeeId() == null
        || c.employeeId().isBlank()
        || c.employeeId().length() > 40
        || c.version() == null
        || c.version() < 0)
      throw new IllegalArgumentException("A valid employee identifier and version are required.");
    ChangeRequest.validateShape(c.field(), c.newValue(), c.reason(), c.effectiveDate());
    if (c.idempotencyKey() == null || !c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException("A valid idempotency key is required.");
    return transactions.run(
        () -> {
          if (c.field().equals("managerId")) store.lockReportingGraph(actor.tenant());
          Employee e = store.employee(actor, c.employeeId(), LocalDate.now(clock), true);
          var previous = store.duplicate(actor, c.idempotencyKey(), LocalDate.now(clock));
          if (previous.isPresent()) {
            var p = previous.get();
            ensureRequestVisibleToOwner(actor, e, p);
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
          if (!actor.canChange(e, c.field())) throw new ChangeRequest.Denied();
          ChangeRequest.validateDate(c.effectiveDate(), LocalDate.now(clock));
          if (e.version() != c.version())
            throw new ChangeRequest.Conflict(
                "This profile has changed. Refresh before submitting.");
          if (c.field().equals("department"))
            organisation.requireDepartment(actor, c.newValue().trim(), c.effectiveDate());
          validatePositionTimeline(
              actor, e.id(), c.field(), c.newValue().trim(), c.effectiveDate());
          validateReportingTimeline(
              actor, e.id(), c.field(), c.newValue().trim(), c.effectiveDate());
          var effectiveEmployee = store.employee(actor, e.id(), c.effectiveDate(), false);
          if (effectiveEmployee.status().equals("Ended"))
            throw new ChangeRequest.Conflict("Employment has ended by that effective date.");
          String old =
              switch (c.field()) {
                case "phone" -> e.phone();
                case "name" -> effectiveEmployee.name();
                case "legalName" -> effectiveEmployee.legalName();
                case "title" -> e.title();
                case "department" -> effectiveEmployee.department();
                case "positionCode" -> effectiveEmployee.positionCode();
                case "managerId" ->
                    effectiveEmployee.managerId() == null ? "" : effectiveEmployee.managerId();
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

  private void ensureRequestVisibleToOwner(Actor actor, Employee employee, ChangeRequest request) {
    if (!request.requestedBy().equals(actor.subject())
        || (ChangeRequest.PRIVATE_FIELDS.contains(request.field())
            && !actor.canReadField(employee, request.field()))) throw new ChangeRequest.Denied();
  }

  public ChangeRequest withdraw(Actor actor, String id, Withdraw c, String correlation) {
    if (c.reason() == null || c.reason().isBlank() || c.reason().length() > 500)
      throw new ChangeRequest.Validation(
          Map.of("reason", "A withdrawal reason of 1–500 characters is required."));
    if (c.idempotencyKey() == null || !c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException("A valid idempotency key is required.");
    return transactions.run(
        () -> {
          var request = store.request(actor, id, true, LocalDate.now(clock));
          var employee = store.employee(actor, request.employeeId(), LocalDate.now(clock), false);
          ensureRequestVisibleToOwner(actor, employee, request);
          var replay = store.withdrawalReplay(actor, c.idempotencyKey());
          if (replay.isPresent()) {
            var prior = replay.get();
            if (!prior.requestId().equals(id) || !prior.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict("Withdrawal key was used for another command.");
            return request;
          }
          request.ensureWithdrawable(actor);
          store.withdraw(
              actor,
              request,
              new com.atlas.modules.corehr.application.model.Withdrawal(
                  id,
                  actor.subject(),
                  c.reason().trim(),
                  c.idempotencyKey(),
                  clock.instant(),
                  correlation));
          return store.request(actor, id, false, LocalDate.now(clock));
        });
  }

  public ChangeRequest decide(Actor actor, String id, Decide c, String correlation) {
    if (c.comment() == null || c.comment().isBlank() || c.comment().length() > 500)
      throw new IllegalArgumentException("A decision comment of 1–500 characters is required.");
    return transactions.run(
        () -> {
          var preview = store.request(actor, id, false, LocalDate.now(clock));
          if (Set.of("name", "managerId").contains(preview.field()) && !actor.hr())
            throw new ChangeRequest.Denied();
          if (preview.field().equals("managerId")) {
            if (!actor.hr()) throw new ChangeRequest.Denied();
            store.lockReportingGraph(actor.tenant());
          }
          var r = store.request(actor, id, true, LocalDate.now(clock));
          var e = store.employee(actor, r.employeeId(), LocalDate.now(clock), true);
          var requesterEmployee = store.identityEmployeeId(actor.tenant(), r.requestedBy());
          if (requesterEmployee.isEmpty() || actor.employeeId().equals(requesterEmployee.get()))
            throw new ChangeRequest.Denied();
          r.ensureDecidable(actor, e, c.decision());
          if (c.decision().equals("APPROVED") && store.hasLaterChange(actor, r))
            throw new ChangeRequest.Conflict(
                "A later effective change exists. Resolve it before approving this request.");
          if (c.decision().equals("APPROVED") && r.field().equals("department"))
            organisation.requireDepartment(actor, r.newValue(), r.effectiveDate());
          if (c.decision().equals("APPROVED"))
            validatePositionTimeline(actor, e.id(), r.field(), r.newValue(), r.effectiveDate());
          if (c.decision().equals("APPROVED"))
            validateReportingTimeline(actor, e.id(), r.field(), r.newValue(), r.effectiveDate());
          store.decide(actor, r, c.decision(), c.comment().trim(), clock.instant(), correlation);
          return store.request(actor, id, false, LocalDate.now(clock));
        });
  }

  private void validateReportingTimeline(
      Actor actor, String employeeId, String field, String managerId, LocalDate from) {
    if (!field.equals("managerId")) return;
    if (store.employmentEndDate(actor.tenant(), managerId).isPresent())
      throw new ChangeRequest.Validation(
          Map.of("newValue", "Choose a manager without a scheduled employment end."));
    if (!store.managerEmployeeIds(actor.tenant(), MANAGER_ROLES).contains(managerId))
      throw new ChangeRequest.Validation(
          Map.of("newValue", "Choose an employee with an HR or manager role."));
    if (store.employee(actor, managerId, from, false).status().equals("Ended"))
      throw new ChangeRequest.Validation(
          Map.of("newValue", "Choose a manager whose employment is active on the proposed date."));
    var boundaries = store.reportingDates(actor.tenant(), from, 257);
    if (boundaries.size() > 256)
      throw new ChangeRequest.Conflict(
          "Reporting validation exceeds 256 future date boundaries; contact HR operations.");
    var dates = new TreeSet<LocalDate>(boundaries);
    dates.add(from);
    for (var date : dates) {
      if (store.employee(actor, managerId, date, false).status().equals("Ended"))
        throw new ChangeRequest.Conflict(
            "The proposed manager has an employment end at a checked reporting boundary.");
      var links = store.reportingLinks(actor.tenant(), date, 10001);
      if (links.size() > 10000)
        throw new ChangeRequest.Conflict(
            "Reporting validation exceeds 10,000 employees; contact HR operations.");
      com.atlas.modules.corehr.domain.service.ReportingHierarchy.validate(
          links, employeeId, managerId, date);
    }
  }

  private void validatePositionTimeline(
      Actor actor, String employeeId, String field, String value, LocalDate from) {
    if (!field.equals("positionCode") && !field.equals("department")) return;
    var dates = new TreeSet<LocalDate>(store.employmentChangeDates(actor, employeeId, from));
    dates.add(from);
    for (var date : dates) {
      var employee = store.employee(actor, employeeId, date, false);
      var position = field.equals("positionCode") ? value : employee.positionCode();
      var department = field.equals("department") ? value : employee.department();
      if (!position.isEmpty()) {
        employee.ensurePositionEffectiveDate(date);
        organisation.requirePosition(actor, position, department, date);
      }
    }
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
