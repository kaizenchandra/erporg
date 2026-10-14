package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.model.DirectoryQuery;
import com.atlas.modules.corehr.application.model.RequestQuery;
import com.atlas.modules.corehr.application.port.in.PeopleUseCases;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
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
class PostgresWorkflowTest {
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate jdbc;

  Actor self() {
    return people.actor("sophia");
  }

  Actor hr() {
    return people.actor("amelia");
  }

  Submit command(LocalDate date) {
    return new Submit(
        "E003",
        "phone",
        "+44 7700 777777",
        date,
        "Integration acceptance",
        0,
        UUID.randomUUID().toString());
  }

  @Test
  void approvedChangeAndAuditAreAtomicAndReplaySafe() {
    var c = command(LocalDate.now(ZoneOffset.UTC));
    var r = people.submit(self(), c, "test-submit");
    assertEquals(r.id(), people.submit(self(), c, "test-repeat").id());
    var approved =
        people.decide(
            hr(), r.id(), new Decide("APPROVED", "Reviewed independently"), "test-approve");
    assertEquals("APPROVED", approved.status());
    assertEquals(c.newValue(), people.employees(self()).getFirst().phone());
    assertEquals(2, people.history(hr(), "E003").size());
    assertEquals(1, people.notifications(self()).size());
    assertEquals(
        people.requests(self()).stream().filter(x -> x.status().equals("SUBMITTED")).count(),
        people.summary(self()).pending());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> people.decide(hr(), r.id(), new Decide("APPROVED", "Duplicate"), "duplicate"));
  }

  @Test
  void futureDateDoesNotApplyEarly() {
    var r = people.submit(self(), command(LocalDate.now(ZoneOffset.UTC).plusDays(30)), "future");
    people.decide(hr(), r.id(), new Decide("APPROVED", "Approved future"), "future-decision");
    assertEquals("+1 202 555 103", people.employees(self()).getFirst().phone());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from hr_version where request_id=?", Integer.class, r.id()));
  }

  @Test
  void rejectsSelfApprovalCrossTenantAndEmployeeJobEdit() {
    var r = people.submit(self(), command(LocalDate.now(ZoneOffset.UTC)), "denials");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.decide(self(), r.id(), new Decide("APPROVED", "Self"), "denied"));
    assertThrows(
        ChangeRequest.Denied.class, () -> people.history(people.actor("outsider"), "E003"));
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            people.submit(
                self(),
                new Submit(
                    "E003",
                    "title",
                    "CEO",
                    LocalDate.now(ZoneOffset.UTC),
                    "Invalid",
                    0,
                    UUID.randomUUID().toString()),
                "denied"));
    assertEquals(1, people.employees(self()).size());
    assertFalse(
        people.employees(people.actor("marcus")).stream().anyMatch(e -> e.id().equals("E006")));
  }

  @Test
  void staleRequestsCanBeRejectedButNotApproved() {
    var first = people.submit(self(), command(LocalDate.now(ZoneOffset.UTC)), "first");
    var second = people.submit(self(), command(LocalDate.now(ZoneOffset.UTC)), "second");
    people.decide(hr(), first.id(), new Decide("APPROVED", "Approve"), "approved");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> people.decide(hr(), second.id(), new Decide("APPROVED", "Stale"), "stale"));
    assertEquals(
        "REJECTED",
        people
            .decide(hr(), second.id(), new Decide("REJECTED", "Stale proposal"), "reject")
            .status());
  }

  @Test
  void queryScopesRespectTenantSelfAndDirectReports() {
    var manager = people.actor("marcus");
    assertEquals(12, people.employees(hr()).size());
    assertEquals(
        List.of("E900"),
        people.employees(people.actor("outsider")).stream().map(Employee::id).toList());
    assertTrue(
        people.employees(manager).stream()
            .allMatch(
                e ->
                    e.id().equals(manager.employeeId())
                        || manager.employeeId().equals(e.managerId())));
    assertTrue(people.requests(people.actor("outsider")).isEmpty());
    var own = people.submit(self(), command(LocalDate.now(ZoneOffset.UTC)), "scoped");
    assertTrue(
        people.requests(self()).stream().allMatch(r -> r.requestedBy().equals(self().subject())));
    assertTrue(people.requests(manager).stream().anyMatch(r -> r.id().equals(own.id())));
    assertTrue(people.requests(hr()).stream().anyMatch(r -> r.id().equals(own.id())));
  }

  @Test
  void directoryPaginationFiltersAndTotalsAreScoped() {
    var first = people.directory(hr(), new DirectoryQuery("", "", "", 0, 5, "asc"));
    var second = people.directory(hr(), new DirectoryQuery("", "", "", 1, 5, "asc"));
    assertEquals(12, first.total());
    assertEquals(5, first.items().size());
    assertTrue(first.items().stream().noneMatch(e -> second.items().contains(e)));
    var filtered = people.directory(hr(), new DirectoryQuery("sOPHia", "", "", 0, 8, "asc"));
    assertEquals(1, filtered.total());
    assertEquals("E003", filtered.items().getFirst().id());
    assertEquals(0, people.directory(hr(), new DirectoryQuery("%", "", "", 0, 8, "asc")).total());
    assertEquals(1, people.directory(self(), new DirectoryQuery("", "", "", 0, 8, "asc")).total());
    var outsider =
        people.directory(people.actor("outsider"), new DirectoryQuery("", "", "", 0, 8, "asc"));
    assertEquals(1, outsider.total());
    assertEquals("E900", outsider.items().getFirst().id());
    assertTrue(
        people.directory(hr(), new DirectoryQuery("", "", "", 99, 8, "asc")).items().isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> new DirectoryQuery("", "", "", -1, 8, "asc"));
    assertThrows(
        IllegalArgumentException.class, () -> new DirectoryQuery("", "", "", 0, 101, "asc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DirectoryQuery("", "", "", 0, 8, "name; drop table"));
  }

  @Test
  void directoryCanReachRecordsBeyondLegacyThousandRowCap() {
    jdbc.update(
        "insert into hr_employee(tenant,id,name,email,phone,title,department,location,status,start_date) select ?, 'S'||n, 'Synthetic '||lpad(n::text,4,'0'), 'synthetic'||n||'@example.test', '12345678', 'Test role', 'Scale test', 'Test', 'Active', date '2026-01-01' from generate_series(1,1005) n",
        hr().tenant());
    var page = people.directory(hr(), new DirectoryQuery("", "Scale test", "", 10, 100, "asc"));
    assertEquals(1017, people.summary(hr()).total());
    assertEquals(1, people.summary(self()).total());
    assertEquals("S1005", people.profile(hr(), "S1005").id());
    assertThrows(ChangeRequest.Denied.class, () -> people.profile(self(), "S1005"));
    assertThrows(
        ChangeRequest.Denied.class, () -> people.profile(people.actor("outsider"), "E003"));
    assertEquals(1005, page.total());
    assertEquals(5, page.items().size());
    assertEquals("S1005", page.items().getLast().id());
    assertEquals(
        1, people.directory(self(), new DirectoryQuery("", "", "", 0, 100, "asc")).total());
  }

  @Test
  void inboxReachesBeyondLegacyCapAndScopesCounts() {
    jdbc.update(
        "insert into hr_request(id,tenant,employee_id,field,old_value,new_value,effective_date,reason,status,requested_by,employee_version,recorded_at,idempotency_key) select 'scale-'||lpad(n::text,4,'0'), ?, 'E003', 'phone', '12345678', '12345679', current_date, 'Synthetic pagination test', 'SUBMITTED', 'sophia', 0, timestamptz '2020-01-01 00:00:00+00', 'scale-key-'||n from generate_series(1,505) n",
        hr().tenant());
    var first = people.requestPage(hr(), new RequestQuery("SUBMITTED", 0, 100));
    var last = people.requestPage(hr(), new RequestQuery("SUBMITTED", 5, 100));
    assertEquals(507, first.total());
    assertEquals(7, last.items().size());
    assertEquals("scale-0505", last.items().getLast().id());
    assertEquals(
        0, people.requestPage(people.actor("outsider"), new RequestQuery("ALL", 0, 100)).total());
    var own = people.requestPage(self(), new RequestQuery("SUBMITTED", 0, 100));
    assertEquals(506, own.total());
    assertTrue(own.items().stream().allMatch(r -> r.requestedBy().equals("sophia")));
    assertEquals(0, people.requestPage(hr(), new RequestQuery("REJECTED", 0, 100)).total());
    assertThrows(IllegalArgumentException.class, () -> new RequestQuery("BOGUS", 0, 12));
    assertThrows(IllegalArgumentException.class, () -> new RequestQuery("ALL", -1, 12));
    assertThrows(IllegalArgumentException.class, () -> new RequestQuery("ALL", 0, 101));
  }

  @Test
  void conflictingReplayRejected() {
    var c = command(LocalDate.now(ZoneOffset.UTC));
    people.submit(self(), c, "original");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            people.submit(
                self(),
                new Submit(
                    c.employeeId(),
                    c.field(),
                    "+44 7700 888888",
                    c.effectiveDate(),
                    c.reason(),
                    c.version(),
                    c.idempotencyKey()),
                "changed"));
  }
}
