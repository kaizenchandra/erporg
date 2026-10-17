package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.EmploymentEndModels.*;
import com.atlas.modules.corehr.application.model.ExitTaskModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5438/atlas?currentSchema=atlas_test",
      "spring.flyway.default-schema=atlas_test",
      "spring.flyway.schemas=atlas_test"
    })
@ActiveProfiles("local")
@Transactional
class EmploymentEndWorkflowTest {
  @Autowired EmploymentEndUseCases ends;
  @Autowired ResignationUseCases resignations;
  @Autowired PeopleUseCases people;
  @Autowired ExitTaskUseCases tasks;
  @Autowired OrganisationBrowseUseCases chart;
  @Autowired JdbcTemplate db;

  String request(String user) {
    var a = people.actor(user);
    var e = people.profile(a, a.employeeId());
    var r =
        resignations.submit(
            user,
            new ResignationCommands.Submit(
                LocalDate.now(ZoneOffset.UTC).plusDays(30),
                "Synthetic resignation",
                BigDecimal.valueOf(e.version()),
                UUID.randomUUID().toString()),
            "submitted");
    resignations.review(
        user.equals("amelia") ? "olivia" : "amelia",
        r.id(),
        new ResignationCommands.Review(
            "APPROVED", "Independent review", BigDecimal.ZERO, UUID.randomUUID().toString()),
        "approved");
    return r.id();
  }

  Confirm command(String id) {
    return new Confirm(
        "Confirm agreed employment end",
        BigDecimal.valueOf(ends.context("amelia", id).employeeVersion()),
        UUID.randomUUID().toString());
  }

  @Test
  void endIsImmutableIdempotentAndKeepsBothDownstreamTargetsPending() {
    var id = request("sophia");
    var c = command(id);
    var record = ends.confirm("amelia", id, c, "end-scheduled");
    assertEquals(record.lastWorkingDate().plusDays(1), record.inactiveDate());
    assertEquals(record, ends.confirm("amelia", id, c, "replay"));
    assertEquals("SCHEDULED", ends.context("amelia", id).phase());
    assertEquals("Active", people.profile(people.actor("amelia"), "E003").status());
    assertEquals(record.newVersion(), people.profile(people.actor("amelia"), "E003").version());
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from hr_employment_end_handoff where employee_id='E003' and status='PENDING'",
            Integer.class));
    assertThrows(
        ChangeRequest.Conflict.class, () -> ends.confirm("olivia", id, command(id), "duplicate"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            ends.confirm(
                "amelia",
                id,
                new Confirm("Changed reason", c.version(), c.idempotencyKey()),
                "conflict"));
    db.execute("set constraints all immediate");
  }

  @Test
  void roleTenantSelfAndMissingFractionalVersionsFailClosed() {
    var id = request("sophia");
    for (var user : List.of("sophia", "marcus", "outsider")) {
      assertThrows(ChangeRequest.Denied.class, () -> ends.context(user, id));
      assertThrows(
          ChangeRequest.Denied.class,
          () ->
              ends.confirm(
                  user,
                  id,
                  new Confirm("Reason", BigDecimal.ZERO, UUID.randomUUID().toString()),
                  "denied"));
    }
    for (var v : Arrays.asList(null, new BigDecimal("0.5"), new BigDecimal("-1")))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ends.confirm(
                  "amelia", id, new Confirm("Reason", v, UUID.randomUUID().toString()), "invalid"));
    var own = request("amelia");
    assertThrows(
        ChangeRequest.Denied.class, () -> ends.confirm("amelia", own, command(own), "self"));
  }

  @Test
  void openCaseAndOtherAssignedTasksMustBeResolvedFirst() {
    var id = request("sophia");
    var t =
        tasks
            .create(
                "amelia",
                id,
                new Create(
                    "E002",
                    "Equipment",
                    "Record receipt",
                    LocalDate.now(ZoneOffset.UTC).plusDays(5),
                    "Exit task",
                    BigDecimal.ZERO,
                    UUID.randomUUID().toString()),
                "task")
            .task();
    assertEquals(1, ends.context("amelia", id).openTasks());
    assertThrows(
        ChangeRequest.Conflict.class, () -> ends.confirm("amelia", id, command(id), "open"));
    tasks.complete(
        "marcus",
        t.id(),
        new Complete("Receipt recorded", BigDecimal.ZERO, UUID.randomUUID().toString()),
        "done");
    ends.confirm("amelia", id, command(id), "scheduled");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            tasks.create(
                "amelia",
                id,
                new Create(
                    "E002",
                    "Another",
                    "Details",
                    LocalDate.now(ZoneOffset.UTC).plusDays(6),
                    "Reason",
                    BigDecimal.ONE,
                    UUID.randomUUID().toString()),
                "closed-case"));
    db.execute("set constraints all immediate");
  }

  @Test
  void directReportsAndStaleEmployeeBlockTheSchedule() {
    var manager = request("marcus");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> ends.confirm("amelia", manager, command(manager), "reports"));
    var id = request("sophia");
    var c = command(id);
    db.update("update hr_employee set version=version+1 where tenant='atlas' and id='E003'");
    assertThrows(ChangeRequest.Conflict.class, () -> ends.confirm("amelia", id, c, "stale"));
  }

  @Test
  void injectedDateProjectsEndedAndDeniesActorWhileHrRetainsHistory() {
    var id = request("sophia");
    var end = ends.confirm("amelia", id, command(id), "scheduled");
    var client = org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource());
    var store = new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(client);
    var hr = people.actor("amelia");
    assertEquals("Active", store.employee(hr, "E003", end.lastWorkingDate(), false).status());
    assertEquals("Ended", store.employee(hr, "E003", end.inactiveDate(), false).status());
    assertThrows(ChangeRequest.Denied.class, () -> store.actor("sophia", end.inactiveDate()));
    var scope = PeopleReadScope.of(hr);
    var page =
        store.directory(
            scope, new DirectoryQuery("E003", "", "Ended", 0, 12, "asc"), end.inactiveDate());
    assertEquals(1, page.total());
    assertFalse(hr.canChange(page.items().getFirst(), "phone"));
    assertEquals(
        "Ended",
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcOrganisationChartStore(client)
            .find(scope, "E003", end.inactiveDate())
            .orElseThrow()
            .node()
            .status());
    assertEquals(
        "Active",
        db.queryForObject(
            "select status from hr_employee where tenant='atlas' and id='E003'", String.class));
    db.execute("set constraints all immediate");
  }

  @Test
  void recordedEndCannotBeDeletedOrLostItsHandoffs() {
    var id = request("sophia");
    ends.confirm("amelia", id, command(id), "scheduled");
    db.execute("savepoint end_audit");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("delete from hr_employment_end where employee_id='E003'"));
    db.execute("rollback to savepoint end_audit");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("delete from hr_employment_end_handoff where employee_id='E003'"));
    db.execute("rollback to savepoint end_audit");
  }

  @Test
  void aScheduledEndCannotReceiveNewTasksOrFutureEmploymentChanges() {
    var id = request("sophia");
    var end = ends.confirm("amelia", id, command(id), "scheduled");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            people.submit(
                people.actor("amelia"),
                new Commands.Submit(
                    "E003",
                    "title",
                    "New role",
                    end.inactiveDate(),
                    "Future change",
                    end.newVersion(),
                    UUID.randomUUID().toString()),
                "ended-date"));
    db.update("update identity_membership set role='MANAGER' where subject='sophia'");
    var colleague = people.profile(people.actor("amelia"), "E004");
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            people.submit(
                people.actor("amelia"),
                new Commands.Submit(
                    "E004",
                    "managerId",
                    "E003",
                    LocalDate.now(ZoneOffset.UTC).plusDays(1),
                    "Temporary reporting",
                    colleague.version(),
                    UUID.randomUUID().toString()),
                "ending-manager"));
    var other = request("marcus");
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            tasks.create(
                "amelia",
                other,
                new Create(
                    "E003",
                    "Work",
                    "Details",
                    end.lastWorkingDate(),
                    "Assign",
                    BigDecimal.ZERO,
                    UUID.randomUUID().toString()),
                "ended-assignee"));
  }

  @Test
  void allApprovedFutureReportingBoundariesMustBeClear() {
    var id = request("sophia");
    db.update("update identity_membership set role='MANAGER' where subject='sophia'");
    var future = ends.context("amelia", id).inactiveDate().plusDays(5);
    var e = people.profile(people.actor("amelia"), "E004");
    var change =
        people.submit(
            people.actor("amelia"),
            new Commands.Submit(
                "E004",
                "managerId",
                "E003",
                future,
                "Planned reporting assignment",
                e.version(),
                UUID.randomUUID().toString()),
            "reporting");
    people.decide(
        people.actor("olivia"),
        change.id(),
        new Commands.Decide("APPROVED", "Independent"),
        "report-approved");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> ends.confirm("amelia", id, command(id), "future-reports"));
  }

  @Test
  void otherCaseOpenAssignmentsMustBeReassignedBeforeEmploymentEnd() {
    var id = request("sophia");
    var other = request("marcus");
    var t =
        tasks
            .create(
                "amelia",
                other,
                new Create(
                    "E003",
                    "Other case work",
                    "Operational instructions",
                    LocalDate.now(ZoneOffset.UTC).plusDays(5),
                    "Assignment",
                    BigDecimal.ZERO,
                    UUID.randomUUID().toString()),
                "assigned")
            .task();
    assertThrows(
        ChangeRequest.Conflict.class, () -> ends.confirm("amelia", id, command(id), "other-case"));
    tasks.manage(
        "amelia",
        t.id(),
        new Manage(
            "REASSIGN",
            "E002",
            "Reallocate before end",
            BigDecimal.ZERO,
            UUID.randomUUID().toString()),
        "reassigned");
    ends.confirm("amelia", id, command(id), "scheduled");
    db.execute("set constraints all immediate");
  }

  @Test
  void pendingResignationCannotEndEmployment() {
    var e = people.profile(people.actor("sophia"), "E003");
    var r =
        resignations.submit(
            "sophia",
            new ResignationCommands.Submit(
                LocalDate.now(ZoneOffset.UTC).plusDays(5),
                "Pending",
                BigDecimal.valueOf(e.version()),
                UUID.randomUUID().toString()),
            "submitted");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            ends.confirm(
                "amelia",
                r.id(),
                new Confirm(
                    "Not approved", BigDecimal.valueOf(e.version()), UUID.randomUUID().toString()),
                "pending"));
  }
}
