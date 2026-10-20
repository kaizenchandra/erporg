package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
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
class PersonalEmailWorkflowTest {
  @Autowired PeopleUseCases people;
  @Autowired OrganisationUseCases organisation;
  @Autowired JdbcTemplate db;

  String key() {
    return UUID.randomUUID().toString();
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  Actor self() {
    return people.actor("sophia");
  }

  Actor hr() {
    return people.actor("amelia");
  }

  Submit command(String value, LocalDate date, String key) {
    return new Submit(
        "E003",
        "personalEmail",
        value,
        date,
        "Supplied personal email",
        people.profile(self(), "E003").version(),
        key);
  }

  ChangeRequest submit(String value) {
    return people.submit(self(), command(value, today(), key()), "personal-email-request");
  }

  void approve(ChangeRequest r) {
    people.decide(
        hr(),
        r.id(),
        new Decide("APPROVED", "Independent private contact review"),
        "personal-email-decision");
  }

  @Test
  void approvalRemovalAndAuditPreserveWorkIdentityAndLateReplay() {
    var before = people.profile(self(), "E003");
    assertEquals("", before.personalEmail());
    var c = command(" Person.Name+tag@Example.test ", today(), key());
    var r = people.submit(self(), c, "email");
    approve(r);
    assertEquals(r.id(), people.submit(self(), c, "late-replay").id());
    var after = people.profile(self(), "E003");
    assertEquals("Person.Name+tag@Example.test", after.personalEmail());
    assertEquals(before.email(), after.email());
    assertEquals(before.name(), after.name());
    assertEquals(before.id(), after.id());
    assertEquals("sophia", self().subject());
    var a =
        people.history(self(), "E003").stream()
            .filter(v -> v.field().equals("personalEmail") && v.action().equals("APPROVED"))
            .findFirst()
            .orElseThrow();
    assertEquals("", a.oldValue());
    assertEquals(after.personalEmail(), a.newValue());
    assertEquals("personal-email-decision", a.correlationId());
    var clear = submit("");
    approve(clear);
    assertEquals("", people.profile(self(), "E003").personalEmail());
    assertEquals(
        2,
        people.history(self(), "E003").stream()
            .filter(v -> v.field().equals("personalEmail") && v.action().equals("APPROVED"))
            .count());
  }

  @Test
  void managerAliasAndTenantCannotReadChangeApproveOrSearch() {
    var r = submit("private-email@example.test");
    var manager = people.actor("marcus");
    assertNull(people.profile(manager, "E003").personalEmail());
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.submit(manager, command("forbidden@example.test", today(), key()), "denied"));
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            people.decide(manager, r.id(), new Decide("APPROVED", "Manager forbidden"), "denied"));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.decide(self(), r.id(), new Decide("APPROVED", "Self forbidden"), "denied"));
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) values('email-self-alias','atlas','E003','Alias','HR')");
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            people.decide(
                people.actor("email-self-alias"),
                r.id(),
                new Decide("APPROVED", "Alias forbidden"),
                "alias"));
    assertThrows(
        ChangeRequest.Denied.class, () -> people.profile(people.actor("outsider"), "E003"));
    assertTrue(people.requests(manager).stream().noneMatch(v -> v.id().equals(r.id())));
    assertTrue(
        people.requestPage(manager, new RequestQuery("ALL", 0, 100)).items().stream()
            .noneMatch(v -> v.id().equals(r.id())));
    approve(r);
    assertTrue(
        people.history(manager, "E003").stream().noneMatch(v -> v.field().equals("personalEmail")));
    assertTrue(
        people.directory(manager, new DirectoryQuery("", "", "", 0, 50, "asc")).items().stream()
            .filter(e -> e.id().equals("E003"))
            .allMatch(e -> e.personalEmail() == null));
    assertEquals(
        0,
        people
            .directory(hr(), new DirectoryQuery("private-email@example.test", "", "", 0, 8, "asc"))
            .total());
  }

  @Test
  void futureAndRejectedAndWithdrawnValuesDoNotApplyEarly() {
    var r =
        people.submit(self(), command("future@example.test", today().plusDays(3), key()), "future");
    approve(r);
    assertEquals("", people.profile(self(), "E003").personalEmail());
    com.atlas.modules.corehr.application.port.out.UnitOfWork direct =
        new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> s) {
            return s.get();
          }
        };
    var at =
        new com.atlas.modules.corehr.application.usecase.PeopleService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(
                JdbcClient.create(db.getDataSource())),
            direct,
            Clock.fixed(
                today().plusDays(3).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            organisation);
    assertEquals("future@example.test", at.profile(self(), "E003").personalEmail());
    var rejected =
        people.submit(
            self(), command("rejected@example.test", today().plusDays(4), key()), "rejected");
    people.decide(hr(), rejected.id(), new Decide("REJECTED", "Keep earlier contact"), "reject");
    var withdrawn =
        people.submit(
            self(), command("withdrawn@example.test", today().plusDays(5), key()), "withdrawn");
    people.withdraw(
        self(), withdrawn.id(), new Withdraw("Withdraw personal contact", key()), "withdraw");
    assertEquals("future@example.test", at.profile(self(), "E003").personalEmail());
  }

  @Test
  void staleApprovalAndChangedKeyCannotOverwrite() {
    var c = command("stale@example.test", today(), key());
    var r = people.submit(self(), c, "submit");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            people.submit(
                self(),
                new Submit(
                    "E003",
                    "personalEmail",
                    "changed@example.test",
                    today(),
                    c.reason(),
                    c.version(),
                    c.idempotencyKey()),
                "changed"));
    var next = submit("current@example.test");
    approve(next);
    assertThrows(ChangeRequest.Conflict.class, () -> approve(r));
    people.decide(hr(), r.id(), new Decide("REJECTED", "Resubmit current profile"), "reject-stale");
    assertEquals("current@example.test", people.profile(self(), "E003").personalEmail());
  }

  @Test
  void javaAndSqlVocabularyAgreeAndBadInputDoesNotMutate() {
    long count = db.queryForObject("select count(*) from hr_request", Long.class);
    for (var v :
        List.of(
            "",
            "Person.Name+tag@Example.test",
            "o'hara@sub.example.test",
            "a..b@example.test",
            "a@-b.test",
            "a@b-.test",
            "a@例.test",
            "a@localhost",
            "a@b.test\n",
            "a".repeat(65) + "@example.test",
            "a@" + "b".repeat(64) + ".test")) {
      assertEquals(
          PersonalEmail.valid(v),
          db.queryForObject("select hr_personal_email_valid(?)", Boolean.class, v),
          v);
      if (!PersonalEmail.valid(v)) assertThrows(ChangeRequest.Validation.class, () -> submit(v));
    }
    assertEquals(count, db.queryForObject("select count(*) from hr_request", Long.class));
  }

  @Test
  void qualifiedSqlRejectsInvalidRequestVersionAndAuditSnapshots() {
    var r = submit("valid@example.test");
    db.execute("set local search_path=pg_catalog");
    for (String statement :
        List.of(
            "insert into atlas_test.hr_request(id,tenant,employee_id,field,old_value,new_value,effective_date,reason,status,requested_by,employee_version,recorded_at,idempotency_key) values('"
                + key()
                + "','atlas','E003','personalEmail','','bad..mail@example.test',current_date,'SQL invalid','SUBMITTED','sophia',"
                + r.employeeVersion()
                + ",now(),'"
                + key()
                + "')",
            "insert into atlas_test.hr_version(tenant,employee_id,field,value,effective_date,recorded_at,request_id) values('atlas','E003','personalEmail','bad@localhost',current_date,now(),'"
                + r.id()
                + "')",
            "insert into atlas_test.hr_audit(tenant,employee_id,request_id,actor,action,field,old_value,new_value,reason,recorded_at,correlation_id) values('atlas','E003','"
                + r.id()
                + "','amelia','APPROVED','personalEmail','bad@localhost','valid@example.test','SQL invalid',now(),'email-probe')")) {
      db.execute("savepoint email_probe");
      var ex =
          assertThrows(
              org.springframework.dao.DataAccessException.class, () -> db.execute(statement));
      assertTrue(ex.getMostSpecificCause().getMessage().contains("Personal email snapshot"));
      db.execute("rollback to savepoint email_probe");
    }
    db.execute("set local search_path=atlas_test");
    assertEquals("", people.profile(self(), "E003").personalEmail());
  }
}
