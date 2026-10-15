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
class WithdrawalWorkflowTest {
  @Autowired PeopleUseCases people;
  @Autowired OrganisationUseCases organisation;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient client;

  Actor self() {
    return people.actor("sophia");
  }

  Actor hr() {
    return people.actor("amelia");
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  Submit input() {
    return new Submit(
        "E003",
        "phone",
        "+44 7700 333222",
        today(),
        "Withdrawal fixture",
        0,
        UUID.randomUUID().toString());
  }

  @Test
  void ownerWithdrawalIsAuditedReplaySafeAndDoesNotApplyValues() {
    var before = people.profile(self(), "E003");
    var r = people.submit(self(), input(), "submit");
    var c = new Withdraw("Entered incorrectly", UUID.randomUUID().toString());
    var withdrawn = people.withdraw(self(), r.id(), c, "withdraw");
    assertEquals("WITHDRAWN", withdrawn.status());
    assertEquals(withdrawn, people.withdraw(self(), r.id(), c, "repeat"));
    assertEquals(before, people.profile(self(), "E003"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from hr_request_withdrawal where request_id=?",
            Integer.class,
            r.id()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from hr_version where request_id=?", Integer.class, r.id()));
    assertTrue(
        people.history(self(), "E003").stream()
            .anyMatch(
                a ->
                    a.action().equals("WITHDRAWN")
                        && a.reason().equals(c.reason())
                        && a.correlationId().equals("withdraw")));
    assertTrue(
        people.requestPage(self(), new RequestQuery("WITHDRAWN", 0, 100)).items().stream()
            .anyMatch(x -> x.id().equals(r.id())));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> people.decide(hr(), r.id(), new Decide("APPROVED", "Too late"), "late"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            people.withdraw(
                self(), r.id(), new Withdraw("Different reason", c.idempotencyKey()), "conflict"));
  }

  @Test
  void rejectsOtherActorsFinalRequestsAndInvalidReason() {
    var r = people.submit(self(), input(), "submit");
    var c = new Withdraw("Withdraw", UUID.randomUUID().toString());
    for (String user : List.of("amelia", "marcus", "outsider"))
      assertThrows(
          ChangeRequest.Denied.class,
          () -> people.withdraw(people.actor(user), r.id(), c, "denied"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            people.withdraw(
                self(), r.id(), new Withdraw(" ", UUID.randomUUID().toString()), "invalid"));
    people.decide(hr(), r.id(), new Decide("APPROVED", "Reviewed"), "approve");
    assertThrows(ChangeRequest.Conflict.class, () -> people.withdraw(self(), r.id(), c, "final"));
  }

  @Test
  void replayAcrossDateBoundaryWorksButNewBackdatedAndMissingVersionCommandsFail() {
    var c = input();
    var r = people.submit(self(), c, "submit");
    var tomorrow =
        new com.atlas.modules.corehr.application.usecase.PeopleService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(client),
            new UnitOfWork() {
              public <T> T run(java.util.function.Supplier<T> operation) {
                return operation.get();
              }
            },
            Clock.offset(Clock.systemUTC(), Duration.ofDays(1)),
            organisation);
    assertEquals(r.id(), tomorrow.submit(self(), c, "next-day").id());
    assertThrows(
        ChangeRequest.Validation.class, () -> tomorrow.submit(self(), input(), "new-backdated"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            people.submit(
                self(),
                new Submit(
                    "E003",
                    "phone",
                    "+44 7700 333999",
                    today(),
                    "Missing version",
                    (Long) null,
                    UUID.randomUUID().toString()),
                "missing"));
  }

  @Test
  void withdrawalCannotExposePrivateRequestAfterRoleDowngrade() {
    var requester = hr();
    var r =
        people.submit(
            requester,
            new Submit(
                "E003",
                "address",
                "Synthetic private address",
                today(),
                "Private fixture",
                0,
                UUID.randomUUID().toString()),
            "private");
    var downgraded =
        new Actor(
            requester.subject(),
            requester.tenant(),
            requester.employeeId(),
            requester.name(),
            "EMPLOYEE");
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            people.withdraw(
                downgraded,
                r.id(),
                new Withdraw("Withdraw", UUID.randomUUID().toString()),
                "private-denied"));
  }
}
