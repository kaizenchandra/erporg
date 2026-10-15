package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
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
class PositionAssignmentWorkflowTest {
  @Autowired PeopleUseCases people;
  @Autowired OrganisationUseCases organisation;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient client;
  @Autowired JdbcTemplate jdbc;

  Actor hr() {
    return people.actor("amelia");
  }

  Actor manager() {
    return people.actor("marcus");
  }

  Actor self() {
    return people.actor("sophia");
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  Position position(String code, String department, LocalDate date) {
    String departmentCode =
        organisation.departments(hr()).stream()
            .filter(d -> d.name().equals(department))
            .findFirst()
            .orElseThrow()
            .code();
    return organisation.createPosition(
        hr(),
        new CreatePosition(
            code,
            "Synthetic assignment role",
            departmentCode,
            date,
            "Assignment fixture",
            UUID.randomUUID().toString()),
        "position");
  }

  Submit command(String field, String value, LocalDate date) {
    return new Submit(
        "E003",
        field,
        value,
        date,
        "Employment assignment change",
        people.profile(manager(), "E003").version(),
        UUID.randomUUID().toString());
  }

  void approve(ChangeRequest r) {
    people.decide(hr(), r.id(), new Decide("APPROVED", "Independent HR review"), "approve");
  }

  @Test
  void assignAndUnassignAreEffectiveAuditedReplaySafeAndScoped() {
    var employee = people.profile(self(), "E003");
    var p = position("ASGN-ROLE", employee.department(), today());
    var c = command("positionCode", p.code(), today());
    var r = people.submit(manager(), c, "submit");
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            people.decide(
                new Actor(
                    "alternate-manager",
                    manager().tenant(),
                    manager().employeeId(),
                    "Same person",
                    "HR"),
                r.id(),
                new Decide("APPROVED", "Alias self review"),
                "alias-denied"));
    assertEquals("", people.profile(self(), "E003").positionCode());
    assertEquals(r.id(), people.submit(manager(), c, "replay").id());
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.decide(self(), r.id(), new Decide("APPROVED", "Self"), "denied"));
    assertThrows(
        ChangeRequest.Denied.class, () -> people.history(people.actor("outsider"), "E003"));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.submit(self(), command("positionCode", p.code(), today()), "self-change"));
    approve(r);
    assertEquals(p.code(), people.profile(self(), "E003").positionCode());
    assertEquals(employee.title(), people.profile(self(), "E003").title());
    assertEquals(1, people.profile(self(), "E003").version());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from hr_position_assignment where request_id=?",
            Integer.class,
            r.id()));
    assertTrue(
        people.history(manager(), "E003").stream()
            .anyMatch(
                a ->
                    a.field().equals("positionCode")
                        && a.action().equals("APPROVED")
                        && a.newValue().equals(p.code())));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> people.decide(hr(), r.id(), new Decide("APPROVED", "Duplicate"), "duplicate"));
    var clear = people.submit(manager(), command("positionCode", "", today()), "unassign");
    approve(clear);
    assertEquals("", people.profile(self(), "E003").positionCode());
    assertEquals(2, people.profile(self(), "E003").version());
    assertNull(
        jdbc.queryForObject(
            "select position_code from hr_position_assignment where request_id=?",
            String.class,
            clear.id()));
  }

  @Test
  void rejectsUnknownWrongDepartmentEarlyAndFutureTimelineConflict() {
    var department = people.profile(self(), "E003").department();
    var p = position("ASGN-FUTURE", department, today().plusDays(10));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> people.submit(manager(), command("positionCode", p.code(), today()), "early"));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> people.submit(manager(), command("positionCode", "MISSING", today()), "unknown"));
    var wrong = position("ASGN-WRONG", "Finance", today());
    assertThrows(
        ChangeRequest.Validation.class,
        () -> people.submit(manager(), command("positionCode", wrong.code(), today()), "wrong"));
    approve(
        people.submit(
            manager(), command("department", "Finance", today().plusDays(20)), "future-transfer"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            people.submit(
                manager(),
                command("positionCode", p.code(), today().plusDays(10)),
                "future-conflict"));
    assertEquals(
        0, jdbc.queryForObject("select count(*) from hr_position_assignment", Integer.class));
  }

  @Test
  void futureAssignmentStaysHiddenUntilDateAndBlocksIncompatibleTransfer() {
    var p = position("ASGN-SCHEDULED", people.profile(self(), "E003").department(), today());
    var r =
        people.submit(manager(), command("positionCode", p.code(), today().plusDays(10)), "future");
    approve(r);
    assertEquals("", people.profile(self(), "E003").positionCode());
    assertEquals(
        p.code(),
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(client)
            .employee(self(), "E003", today().plusDays(10), false)
            .positionCode());
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            people.submit(
                manager(), command("department", "Finance", today()), "transfer-conflict"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            people.submit(
                manager(),
                command("department", "Finance", today().plusDays(20)),
                "later-conflict"));
  }

  @Test
  void approvalRevalidatesAndRejectsStaleEmployeeWithoutAssignment() {
    var p = position("ASGN-STALE", people.profile(self(), "E003").department(), today());
    var r = people.submit(manager(), command("positionCode", p.code(), today()), "assignment");
    approve(people.submit(manager(), command("department", "Finance", today()), "transfer"));
    assertThrows(ChangeRequest.Conflict.class, () -> approve(r));
    people.decide(
        hr(), r.id(), new Decide("REJECTED", "Resubmit against current department"), "reject");
    assertEquals("", people.profile(self(), "E003").positionCode());
    assertEquals(
        0, jdbc.queryForObject("select count(*) from hr_position_assignment", Integer.class));
  }

  @Test
  void sameInstantVersionsStillApplyInApprovalOrder() {
    var p = position("ASGN-SAME-TIME", people.profile(self(), "E003").department(), today());
    var fixed =
        new com.atlas.modules.corehr.application.usecase.PeopleService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(client),
            new UnitOfWork() {
              public <T> T run(java.util.function.Supplier<T> operation) {
                return operation.get();
              }
            },
            Clock.fixed(Instant.now(), ZoneOffset.UTC),
            organisation);
    var assigned =
        fixed.submit(manager(), command("positionCode", p.code(), today()), "same-submit");
    fixed.decide(
        hr(), assigned.id(), new Decide("APPROVED", "Assign at fixed instant"), "same-approve");
    var cleared = fixed.submit(manager(), command("positionCode", "", today()), "same-clear");
    fixed.decide(
        hr(), cleared.id(), new Decide("APPROVED", "Clear at fixed instant"), "same-clear-approve");
    assertEquals("", fixed.profile(self(), "E003").positionCode());
    assertEquals(2, fixed.profile(self(), "E003").version());
  }

  @Test
  void unassignmentAllowsSubsequentApprovedTransferAndIntegrityRejectsUnapprovedLink() {
    var p = position("ASGN-TRANSFER", people.profile(self(), "E003").department(), today());
    approve(people.submit(manager(), command("positionCode", p.code(), today()), "assign"));
    approve(people.submit(manager(), command("positionCode", "", today()), "clear"));
    approve(people.submit(manager(), command("department", "Finance", today()), "transfer"));
    assertEquals("Finance", people.profile(self(), "E003").department());
    var next = position("ASGN-FINANCE", "Finance", today());
    var pending =
        people.submit(manager(), command("positionCode", next.code(), today()), "pending");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "insert into hr_position_assignment(tenant,employee_id,position_code,effective_date,recorded_at,request_id) values(?, 'E003', ?, ?, ?, ?)",
                hr().tenant(),
                next.code(),
                today(),
                java.sql.Timestamp.from(Instant.now()),
                pending.id()));
  }
}
