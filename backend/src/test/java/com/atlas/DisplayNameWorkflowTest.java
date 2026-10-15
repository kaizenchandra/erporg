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
class DisplayNameWorkflowTest {
  @Autowired PeopleUseCases people;
  @Autowired OrganisationUseCases organisation;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient client;

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  Actor self() {
    return people.actor("sophia");
  }

  Actor hr() {
    return people.actor("amelia");
  }

  Actor manager() {
    return people.actor("marcus");
  }

  Submit input(String employee, String name, LocalDate date, long version) {
    return new Submit(
        employee,
        "name",
        name,
        date,
        "Display name maintenance",
        version,
        UUID.randomUUID().toString());
  }

  @Test
  void approvedNamePropagatesAcrossProfileDirectoryActorAndRequestsWithoutIdentityChange() {
    var before = people.profile(self(), "E003");
    var c = input("E003", "Zoë 李", today(), before.version());
    var r = people.submit(self(), c, "name-submit");
    assertEquals(r.id(), people.submit(self(), c, "name-repeat").id());
    assertEquals(before.name(), people.profile(self(), "E003").name());
    people.decide(
        hr(), r.id(), new Decide("APPROVED", "HR confirmed display name"), "name-approve");
    var after = people.profile(self(), "E003");
    assertEquals(c.newValue(), after.name());
    assertEquals(before.id(), after.id());
    assertEquals(before.email(), after.email());
    assertEquals(before.version() + 1, after.version());
    assertEquals(c.newValue(), people.actor("sophia").name());
    assertEquals(c.newValue(), people.employees(self()).getFirst().name());
    assertEquals(
        1,
        people.directory(manager(), new DirectoryQuery(c.newValue(), "", "", 0, 8, "asc")).total());
    assertTrue(
        people.requests(hr()).stream()
            .anyMatch(x -> x.id().equals(r.id()) && x.employeeName().equals(c.newValue())));
    assertTrue(
        people.history(self(), "E003").stream()
            .anyMatch(
                h ->
                    h.field().equals("name")
                        && h.action().equals("APPROVED")
                        && h.oldValue().equals(before.name())
                        && h.newValue().equals(c.newValue())));
  }

  @Test
  void futureNameWaitsAndManagerCandidatesUseEffectiveNames() {
    var actor = people.actor("olivia");
    var before = people.profile(actor, "E005");
    var c = input("E005", "Future manager name", today().plusDays(10), before.version());
    var r = people.submit(actor, c, "future-name");
    people.decide(hr(), r.id(), new Decide("APPROVED", "Future name reviewed"), "future-approve");
    assertEquals(before.name(), people.profile(actor, "E005").name());
    assertEquals(0, people.managerCandidates(hr(), new ManagerQuery(c.newValue(), 0, 8)).total());
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
    assertEquals(c.newValue(), future.profile(actor, "E005").name());
    assertEquals(c.newValue(), future.actor("olivia").name());
    assertEquals(1, future.managerCandidates(hr(), new ManagerQuery(c.newValue(), 0, 8)).total());
  }

  @Test
  void deniesManagerEditsReviewsAndStaleApprovalsWhileRejectionPreservesName() {
    var c = input("E003", "Proposed name", today(), 0);
    assertThrows(ChangeRequest.Denied.class, () -> people.submit(manager(), c, "team-edit"));
    assertThrows(
        ChangeRequest.Denied.class, () -> people.submit(people.actor("outsider"), c, "tenant"));
    var r = people.submit(self(), c, "name");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.decide(self(), r.id(), new Decide("APPROVED", "Self"), "self"));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.decide(manager(), r.id(), new Decide("APPROVED", "Manager"), "manager"));
    var competing = people.submit(self(), input("E003", "Another name", today(), 0), "competing");
    people.decide(hr(), competing.id(), new Decide("APPROVED", "Independent review"), "winner");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> people.decide(hr(), r.id(), new Decide("APPROVED", "Stale"), "stale"));
    people.decide(hr(), r.id(), new Decide("REJECTED", "Refresh profile"), "reject");
    assertEquals("Another name", people.profile(self(), "E003").name());
  }
}
