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
class BirthDateWorkflowTest {
  @Autowired PeopleUseCases people;
  @Autowired OrganisationUseCases organisation;
  @Autowired JdbcTemplate db;

  Actor self() {
    return people.actor("sophia");
  }

  Actor hr() {
    return people.actor("amelia");
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  Submit command(String value, LocalDate date, String key) {
    return new Submit(
        "E003",
        "birthDate",
        value,
        date,
        "Supplied personal date",
        people.profile(self(), "E003").version(),
        key);
  }

  ChangeRequest request(String value) {
    return people.submit(self(), command(value, today(), key()), "birth-request");
  }

  @Test
  void approvalAppliesPrivateValueWithAuditWithoutChangingDisplayIdentity() {
    var before = people.profile(self(), "E003");
    assertEquals("", before.birthDate());
    var c = command("1992-02-29", today(), key());
    var r = people.submit(self(), c, "recorded-date");
    assertEquals(r.id(), people.submit(self(), c, "replay").id());
    people.decide(
        hr(), r.id(), new Decide("APPROVED", "Independent personal-data review"), "decision-date");
    var after = people.profile(self(), "E003");
    assertEquals("1992-02-29", after.birthDate());
    assertEquals(before.name(), after.name());
    assertEquals(before.email(), after.email());
    assertEquals(before.id(), after.id());
    var audit =
        people.history(self(), "E003").stream()
            .filter(a -> a.field().equals("birthDate") && a.action().equals("APPROVED"))
            .findFirst()
            .orElseThrow();
    assertEquals("", audit.oldValue());
    assertEquals("1992-02-29", audit.newValue());
    assertEquals("decision-date", audit.correlationId());
    var page = people.directory(self(), new DirectoryQuery("", "", "", 0, 8, "asc"));
    assertEquals("1992-02-29", page.items().getFirst().birthDate());
    assertEquals(
        0, people.directory(hr(), new DirectoryQuery("1992-02-29", "", "", 0, 8, "asc")).total());
  }

  @Test
  void managerTenantAndAliasDoNotReadEditReviewOrCountTeamBirthDates() {
    var r = request("1992-02-29");
    var manager = people.actor("marcus");
    assertNull(people.profile(manager, "E003").birthDate());
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.submit(manager, command("1991-01-01", today(), key()), "manager"));
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            people.decide(
                manager, r.id(), new Decide("APPROVED", "Forbidden team review"), "manager"));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.decide(self(), r.id(), new Decide("APPROVED", "Self review"), "self"));
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) values('birth-self-alias','atlas','E003','Alias','HR')");
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            people.decide(
                people.actor("birth-self-alias"),
                r.id(),
                new Decide("APPROVED", "Alias self review"),
                "alias"));
    assertThrows(
        ChangeRequest.Denied.class, () -> people.profile(people.actor("outsider"), "E003"));
    assertTrue(people.requests(manager).stream().noneMatch(x -> x.id().equals(r.id())));
    assertTrue(
        people.history(manager, "E003").stream().noneMatch(x -> x.field().equals("birthDate")));
    var page = people.requestPage(manager, new RequestQuery("ALL", 0, 100));
    assertTrue(page.items().stream().noneMatch(x -> x.id().equals(r.id())));
  }

  @Test
  void rejectsInvalidFutureAndAfterOriginalStartWithoutMutation() {
    long before =
        db.queryForObject("select count(*) from hr_request where field='birthDate'", Long.class);
    for (String s :
        List.of(
            "",
            "2023-02-29",
            "0000-01-01",
            "2000-01-01T00:00:00Z",
            today().plusDays(1).toString(),
            "2024-01-01")) assertThrows(ChangeRequest.Validation.class, () -> request(s));
    assertEquals(
        before,
        db.queryForObject("select count(*) from hr_request where field='birthDate'", Long.class));
  }

  @Test
  void futureApprovalAndRejectionPreserveCurrentDateUntilEffectiveBoundary() {
    var r = people.submit(self(), command("1992-02-29", today().plusDays(3), key()), "future");
    people.decide(hr(), r.id(), new Decide("APPROVED", "Scheduled correction"), "approved");
    assertEquals("", people.profile(self(), "E003").birthDate());
    var jdbc = JdbcClient.create(db.getDataSource());
    com.atlas.modules.corehr.application.port.out.UnitOfWork direct =
        new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> s) {
            return s.get();
          }
        };
    var at =
        new com.atlas.modules.corehr.application.usecase.PeopleService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(jdbc),
            direct,
            Clock.fixed(
                today().plusDays(3).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            organisation);
    assertEquals("1992-02-29", at.profile(self(), "E003").birthDate());
    var another =
        people.submit(self(), command("1993-01-01", today().plusDays(4), key()), "reject");
    people.decide(
        hr(), another.id(), new Decide("REJECTED", "Keep previous personal record"), "rejected");
    assertEquals("1992-02-29", at.profile(self(), "E003").birthDate());
  }

  @Test
  void staleApprovalAndChangedReplayDoNotOverwritePersonalRecord() {
    var c = command("1992-02-29", today(), key());
    var r = people.submit(self(), c, "birth");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            people.submit(
                self(),
                new Submit(
                    "E003",
                    "birthDate",
                    "1993-01-01",
                    today(),
                    c.reason(),
                    c.version(),
                    c.idempotencyKey()),
                "conflict"));
    var phone =
        people.submit(
            self(),
            new Submit(
                "E003",
                "phone",
                "+44 7700 900555",
                today(),
                "Another record change",
                c.version(),
                key()),
            "phone");
    people.decide(hr(), phone.id(), new Decide("APPROVED", "Current version advanced"), "advance");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            people.decide(hr(), r.id(), new Decide("APPROVED", "Stale personal record"), "stale"));
    people.decide(
        hr(), r.id(), new Decide("REJECTED", "Resubmit against current version"), "reject");
    assertEquals("", people.profile(self(), "E003").birthDate());
  }

  @Test
  void sqlGuardsRejectInvalidRequestVersionAndAuditWithoutPublishingValues() {
    var r = request("1992-02-29");
    for (String value : List.of("2023-02-29", "0000-01-01", "2024-01-01", "1992-02-29T00:00:00Z")) {
      db.execute("savepoint birth_probe");
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              db.update(
                  "insert into hr_request(id,tenant,employee_id,field,old_value,new_value,effective_date,reason,status,requested_by,employee_version,recorded_at,idempotency_key) values(?,'atlas','E003','birthDate','',?,?,'Synthetic SQL fixture','SUBMITTED','sophia',?,now(),?)",
                  key(),
                  value,
                  today(),
                  r.employeeVersion(),
                  key()));
      db.execute("rollback to savepoint birth_probe");
    }
    db.execute("savepoint birth_version");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "insert into hr_version(tenant,employee_id,field,value,effective_date,recorded_at,request_id) values('atlas','E003','birthDate','2023-02-29',?,now(),?)",
                today(),
                r.id()));
    db.execute("rollback to savepoint birth_version");
    db.execute("savepoint birth_audit");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "insert into hr_audit(tenant,employee_id,request_id,actor,action,field,old_value,new_value,reason,recorded_at,correlation_id) values('atlas','E003',?,'amelia','APPROVED','birthDate','','2024-01-01','Synthetic audit',now(),'date-probe')",
                r.id()));
    db.execute("rollback to savepoint birth_audit");
  }

  @Test
  void qualifiedAuditProbeUsesItsOwnSchemaDespiteCallerSearchPath() {
    var r = request("1992-02-29");
    people.decide(hr(), r.id(), new Decide("APPROVED", "Supplied personal record"), "schema");
    db.execute("set local search_path=pg_catalog");
    db.execute(
        """
        DO $probe$ BEGIN
          BEGIN
            UPDATE atlas_test.hr_audit SET reason=reason WHERE field='birthDate' AND employee_id='E003';
            RAISE EXCEPTION 'Immutable audit did not reject update' USING ERRCODE='ZX001';
          EXCEPTION WHEN raise_exception THEN
            IF SQLERRM <> 'Audit events are append only' THEN RAISE; END IF;
          END;
        END $probe$;
        """);
    db.execute("set local search_path=atlas_test");
    assertEquals("1992-02-29", people.profile(self(), "E003").birthDate());
  }
}
