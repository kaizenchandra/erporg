package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
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
class ReportingWorkflowTest {
  @Autowired PeopleUseCases people;
  @Autowired OrganisationUseCases organisation;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient client;

  Actor hr() {
    return people.actor("amelia");
  }

  Actor old() {
    return people.actor("marcus");
  }

  Actor self() {
    return people.actor("sophia");
  }

  Actor next() {
    jdbc.update(
        "insert into identity_membership values('next-manager','atlas','E005','Next Manager','MANAGER') on conflict do nothing");
    return people.actor("next-manager");
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  Submit command(String employee, String manager, LocalDate date, Actor actor) {
    return new Submit(
        employee,
        "managerId",
        manager,
        date,
        "Reporting change",
        people.profile(actor, employee).version(),
        UUID.randomUUID().toString());
  }

  void approve(Actor actor, ChangeRequest r) {
    people.decide(actor, r.id(), new Decide("APPROVED", "HR reviewed access impact"), "approve");
  }

  @Test
  void currentChangeMovesAllReadScopesAndRetainsAudit() {
    var next = next();
    long beforeOld = people.summary(old()).total(), beforeNext = people.summary(next).total();
    var phone =
        people.submit(
            self(),
            new Submit(
                "E003",
                "phone",
                "+44 7700 900998",
                today(),
                "Pending contact",
                0,
                UUID.randomUUID().toString()),
            "contact");
    var input = command("E003", "E005", today(), old());
    var reporting = people.submit(old(), input, "reporting");
    assertEquals(reporting.id(), people.submit(old(), input, "repeat").id());
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            people.decide(
                old(), reporting.id(), new Decide("APPROVED", "Manager forbidden"), "denied"));
    approve(hr(), reporting);
    assertEquals("E005", people.profile(self(), "E003").managerId());
    assertThrows(ChangeRequest.Denied.class, () -> people.profile(old(), "E003"));
    assertThrows(ChangeRequest.Denied.class, () -> people.history(old(), "E003"));
    assertNull(people.profile(next, "E003").address());
    assertEquals(beforeOld - 1, people.summary(old()).total());
    assertEquals(beforeNext + 1, people.summary(next).total());
    assertEquals(
        0, people.directory(old(), new DirectoryQuery("E003", "", "", 0, 8, "asc")).total());
    assertEquals(
        1, people.directory(next, new DirectoryQuery("E003", "", "", 0, 8, "asc")).total());
    assertTrue(people.employees(old()).stream().noneMatch(e -> e.id().equals("E003")));
    assertTrue(people.employees(next).stream().anyMatch(e -> e.id().equals("E003")));
    assertTrue(people.requests(old()).stream().noneMatch(r -> r.id().equals(phone.id())));
    assertTrue(people.requests(next).stream().anyMatch(r -> r.id().equals(phone.id())));
    assertTrue(
        people.requestPage(old(), new RequestQuery("ALL", 0, 100)).items().stream()
            .noneMatch(r -> r.id().equals(phone.id())));
    assertTrue(
        people.requestPage(next, new RequestQuery("ALL", 0, 100)).items().stream()
            .anyMatch(r -> r.id().equals(phone.id())));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from hr_reporting_assignment where request_id=?",
            Integer.class,
            reporting.id()));
    assertTrue(
        people.history(next, "E003").stream()
            .anyMatch(h -> h.field().equals("managerId") && h.action().equals("APPROVED")));
  }

  @Test
  void futureChangeActivatesAccessOnlyOnEffectiveDate() {
    var next = next();
    var r = people.submit(old(), command("E003", "E005", today().plusDays(10), old()), "future");
    approve(hr(), r);
    assertEquals("E002", people.profile(old(), "E003").managerId());
    assertThrows(ChangeRequest.Denied.class, () -> people.profile(next, "E003"));
    var future =
        new com.atlas.modules.corehr.application.usecase.PeopleService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(client),
            new UnitOfWork() {
              public <T> T run(java.util.function.Supplier<T> op) {
                return op.get();
              }
            },
            Clock.offset(Clock.systemUTC(), Duration.ofDays(11)),
            organisation);
    assertThrows(ChangeRequest.Denied.class, () -> future.profile(old(), "E003"));
    assertEquals("E005", future.profile(next, "E003").managerId());
    assertEquals(
        0, future.directory(old(), new DirectoryQuery("E003", "", "", 0, 8, "asc")).total());
    assertEquals(
        1, future.directory(next, new DirectoryQuery("E003", "", "", 0, 8, "asc")).total());
  }

  @Test
  void rejectsSelfDescendantUnknownTenantAndIneligibleManager() {
    for (String id : List.of("E003", "E004", "E900", "MISSING"))
      assertThrows(
          ChangeRequest.Validation.class,
          () -> people.submit(old(), command("E003", id, today(), old()), "invalid"));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> people.submit(hr(), command("E001", "E002", today(), hr()), "cycle"));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.submit(self(), command("E003", "E001", today(), self()), "self-change"));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.managerCandidates(self(), new ManagerQuery("", 0, 8)));
    assertEquals(
        0,
        people
            .managerCandidates(
                new Actor("other-hr", "other", "E900", "Other", "HR"), new ManagerQuery("", 0, 8))
            .total());
  }

  @Test
  void approvedFutureBoundaryPreventsCycleAndApprovalRevalidates() {
    var pending =
        people.submit(hr(), command("E002", "E005", today().plusDays(10), hr()), "pending");
    var other = people.actor("olivia");
    var future =
        people.submit(other, command("E005", "E002", today().plusDays(20), other), "future-parent");
    approve(hr(), future);
    assertThrows(ChangeRequest.Validation.class, () -> approve(other, pending));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            people.submit(
                hr(), command("E002", "E005", today().plusDays(10), hr()), "future-cycle"));
    assertEquals(
        "SUBMITTED",
        people.requests(hr()).stream()
            .filter(r -> r.id().equals(pending.id()))
            .findFirst()
            .orElseThrow()
            .status());
  }

  @Test
  void oversizedHierarchyIsRejectedWithoutTruncatedValidation() {
    jdbc.update(
        "insert into hr_employee(tenant,id,name,email,phone,title,department,location,status,start_date) select 'atlas','REPORT-'||n,'Synthetic hierarchy fixture','report-'||n||'@example.test','12345678','Analyst','Design','Remote','Active',current_date from generate_series(1,10001)n");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> people.submit(old(), command("E003", "E001", today(), old()), "oversized"));
    assertEquals(
        0, jdbc.queryForObject("select count(*) from hr_reporting_assignment", Integer.class));
  }

  @Test
  void candidatesAreMinimalPagedAndAssignmentLedgerRejectsUnapproved() {
    var page = people.managerCandidates(old(), new ManagerQuery("", 0, 1));
    assertEquals(3, page.total());
    assertEquals(1, page.items().size());
    assertEquals(0, people.managerCandidates(old(), new ManagerQuery("%", 0, 8)).total());
    assertThrows(IllegalArgumentException.class, () -> new ManagerQuery("", 0, 101));
    var r = people.submit(old(), command("E003", "E001", today(), old()), "pending");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "insert into hr_reporting_assignment values(?, 'E003', 'E001', ?, ?, ?)",
                hr().tenant(),
                today(),
                java.sql.Timestamp.from(Instant.now()),
                r.id()));
  }
}
