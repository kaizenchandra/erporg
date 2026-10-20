package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.port.in.*;
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
class EntityAssignmentWorkflowTest {
  @Autowired PeopleUseCases people;
  @Autowired LegalEntitiesUseCases entities;
  @Autowired LegalEntityRetirementUseCases retirements;
  @Autowired EntityAssignmentUseCases contexts;
  @Autowired JdbcTemplate db;

  LocalDate day() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  Actor hr() {
    return people.actor("amelia");
  }

  Actor reviewer() {
    return people.actor("olivia");
  }

  Actor self() {
    return people.actor("sophia");
  }

  void create(String code) {
    create(code, day());
  }

  void create(String code, LocalDate date) {
    entities.create(
        hr(),
        new LegalEntityModels.Create(
            new LegalEntityModels.Input(
                code, "Declared synthetic company", "GB", "GBP", "Europe/London", date),
            "Unverified reference only",
            key()),
        "entity-created");
  }

  Submit command(String code, LocalDate date) {
    return new Submit(
        "E003",
        "legalEntityCode",
        code,
        date,
        "Declared entity assignment",
        people.profile(hr(), "E003").version(),
        key());
  }

  ChangeRequest submit(String code, LocalDate date) {
    return people.submit(hr(), command(code, date), "entity-submit");
  }

  void approve(ChangeRequest r) {
    people.decide(
        reviewer(),
        r.id(),
        new Decide("APPROVED", "Independent declared-reference review"),
        "entity-approved");
  }

  @Test
  void approvalAndUnassignmentArePrivateEffectiveAndRetainImmutableHistory() {
    create("ENTITY-BASE");
    var before = people.profile(self(), "E003");
    var c = command("ENTITY-BASE", day());
    var r = people.submit(hr(), c, "submit");
    assertEquals("", people.profile(self(), "E003").legalEntityCode());
    assertEquals("GB", contexts.context(reviewer(), r.id()).reference().countryCode());
    approve(r);
    assertEquals(r.id(), people.submit(hr(), c, "late-replay").id());
    assertEquals("ENTITY-BASE", people.profile(self(), "E003").legalEntityCode());
    assertEquals(before.email(), people.profile(self(), "E003").email());
    var manager = people.actor("marcus");
    assertNull(people.profile(manager, "E003").legalEntityCode());
    assertNull(
        people
            .directory(manager, new DirectoryQuery("E003", "", "", 0, 10, "asc"))
            .items()
            .getFirst()
            .legalEntityCode());
    assertTrue(
        people.history(manager, "E003").stream()
            .noneMatch(a -> a.field().equals("legalEntityCode")));
    assertTrue(
        people.requests(manager).stream().noneMatch(a -> a.field().equals("legalEntityCode")));
    assertThrows(ChangeRequest.Denied.class, () -> contexts.context(self(), r.id()));
    assertTrue(retirements.context(hr(), "ENTITY-BASE").assignmentRecorded());
    approve(submit("", day()));
    assertEquals("", people.profile(self(), "E003").legalEntityCode());
    assertEquals(
        2, db.queryForObject("select count(*) from hr_legal_entity_assignment", Integer.class));
    assertEquals(
        2, db.queryForObject("select count(*) from hr_entity_request_origin", Integer.class));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            retirements.submit(
                hr(),
                "ENTITY-BASE",
                new LegalEntityRetirementModels.Submit(
                    day().plusDays(1), "Retire used reference", BigDecimal.ZERO, key()),
                "blocked"));
  }

  @Test
  void futureRejectedStaleAndInvalidReferencesDoNotApply() {
    create("ENTITY-FUTURE", day().plusDays(1));
    assertThrows(ChangeRequest.Validation.class, () -> submit("MISSING", day()));
    assertThrows(ChangeRequest.Validation.class, () -> submit("ENTITY-FUTURE", day()));
    var r = submit("ENTITY-FUTURE", day().plusDays(1));
    approve(r);
    assertEquals("", people.profile(self(), "E003").legalEntityCode());
    var jdbc =
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(
            org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource()));
    assertEquals(
        "ENTITY-FUTURE", jdbc.employee(hr(), "E003", day().plusDays(1), false).legalEntityCode());
    var clear = submit("", day().plusDays(2));
    var stale = submit("", day().plusDays(3));
    approve(clear);
    assertThrows(ChangeRequest.Conflict.class, () -> approve(stale));
    people.decide(
        reviewer(), stale.id(), new Decide("REJECTED", "Stale source snapshot"), "rejected");
    assertEquals(
        2, db.queryForObject("select count(*) from hr_legal_entity_assignment", Integer.class));
  }

  @Test
  void retirementPendingAndApprovedBlockWhileRejectionRestoresEligibility() {
    create("ENTITY-RETIRE");
    var assignment = submit("ENTITY-RETIRE", day().plusDays(1));
    var retirement =
        retirements.submit(
            hr(),
            "ENTITY-RETIRE",
            new LegalEntityRetirementModels.Submit(
                day().plusDays(2), "Review declared retirement", BigDecimal.ZERO, key()),
            "retire");
    assertTrue(contexts.context(reviewer(), assignment.id()).retirementBlocked());
    assertThrows(ChangeRequest.Conflict.class, () -> approve(assignment));
    assertThrows(ChangeRequest.Conflict.class, () -> submit("ENTITY-RETIRE", day().plusDays(3)));
    retirements.decide(
        reviewer(),
        "ENTITY-RETIRE",
        retirement.id(),
        new LegalEntityRetirementModels.Review(
            "REJECTED", "Keep this reference available", BigDecimal.ZERO, key()),
        "retire-rejected");
    assertFalse(contexts.context(reviewer(), assignment.id()).retirementBlocked());
    approve(assignment);
    create("ENTITY-RETIRED");
    var retired =
        retirements.submit(
            hr(),
            "ENTITY-RETIRED",
            new LegalEntityRetirementModels.Submit(
                day().plusDays(2), "Approve declared retirement", BigDecimal.ZERO, key()),
            "retire");
    retirements.decide(
        reviewer(),
        "ENTITY-RETIRED",
        retired.id(),
        new LegalEntityRetirementModels.Review(
            "APPROVED", "Independently retired", BigDecimal.ZERO, key()),
        "retire-approved");
    assertThrows(ChangeRequest.Conflict.class, () -> submit("ENTITY-RETIRED", day()));
  }

  @Test
  void originalRequesterEmployeeCannotReviewAfterSubjectReassignment() {
    create("ENTITY-ORIGIN");
    var c = command("ENTITY-ORIGIN", day());
    var r = people.submit(hr(), c, "original");
    for (var actor : List.of(hr(), self(), people.actor("marcus"), people.actor("outsider")))
      assertThrows(
          ChangeRequest.Denied.class,
          () ->
              people.decide(actor, r.id(), new Decide("APPROVED", "Forbidden identity"), "denied"));
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) values('entity-origin-alias','atlas','E001','Alias','HR')");
    db.update("update identity_membership set employee_id='E004' where subject='amelia'");
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            people.decide(
                people.actor("entity-origin-alias"),
                r.id(),
                new Decide("APPROVED", "Original employee alias"),
                "alias"));
    assertThrows(ChangeRequest.Denied.class, () -> people.submit(hr(), c, "moved-replay"));
    approve(r);
    assertEquals(
        "E001",
        db.queryForObject(
            "select requester_employee_id from hr_entity_request_origin where request_id=?",
            String.class,
            r.id()));
  }

  @Test
  void commandsAndContextsDenyManagerSelfAndOtherTenant() {
    create("ENTITY-SCOPE");
    var c = command("ENTITY-SCOPE", day());
    for (var a : List.of(self(), people.actor("marcus"), people.actor("outsider")))
      assertThrows(ChangeRequest.Denied.class, () -> people.submit(a, c, "denied"));
    var r = people.submit(hr(), c, "submit");
    for (var a : List.of(self(), people.actor("marcus"), people.actor("outsider")))
      assertThrows(ChangeRequest.Denied.class, () -> contexts.context(a, r.id()));
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            contexts.context(
                hr(),
                people.requests(hr()).stream()
                    .filter(x -> !x.field().equals("legalEntityCode"))
                    .findFirst()
                    .orElseThrow()
                    .id()));
  }

  @Test
  void qualifiedSqlRejectsImmutableAndForgedSourcesAndUsedRetirement() {
    create("ENTITY-SQL");
    approve(submit("ENTITY-SQL", day()));
    db.execute("set local search_path=pg_catalog");
    for (var statement :
        List.of(
            "update atlas_test.hr_legal_entity_assignment set entity_code=entity_code",
            "delete from atlas_test.hr_legal_entity_assignment",
            "truncate atlas_test.hr_legal_entity_assignment",
            "update atlas_test.hr_entity_request_origin set requester_employee_id=requester_employee_id",
            "delete from atlas_test.hr_entity_request_origin",
            "truncate atlas_test.hr_entity_request_origin",
            "update atlas_test.hr_version set value=value where field='legalEntityCode'",
            "delete from atlas_test.hr_version where field='legalEntityCode'")) {
      db.execute("savepoint entity_sql");
      var ex =
          assertThrows(
              org.springframework.dao.DataAccessException.class, () -> db.execute(statement));
      assertTrue(ex.getMostSpecificCause().getMessage().contains("append only"));
      db.execute("rollback to savepoint entity_sql");
    }
    for (var statement :
        List.of(
            "update atlas_test.hr_request set new_value='OTHER' where field='legalEntityCode'",
            "update atlas_test.hr_request set field='phone' where field='legalEntityCode'",
            "update atlas_test.hr_request set requested_by='olivia' where field='legalEntityCode'",
            "delete from atlas_test.hr_request where field='legalEntityCode'")) {
      db.execute("savepoint entity_sql");
      var failure =
          assertThrows(
              org.springframework.dao.DataAccessException.class, () -> db.execute(statement));
      assertTrue(failure.getMostSpecificCause().getMessage().contains("provenance is immutable"));
      db.execute("rollback to savepoint entity_sql");
    }
    db.execute("savepoint entity_sql");
    var ex =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () ->
                db.update(
                    "insert into atlas_test.hr_legal_entity_retirement_request(id,tenant,code,ordinal,source_version,effective_date,reason,requested_by,requester_employee_id,recorded_at,correlation_id,idempotency_key) values(?,'atlas','ENTITY-SQL',1,0,current_date,'Forbidden used retirement','amelia','E001',now(),'forged',?)",
                    key(),
                    key()));
    assertTrue(ex.getMostSpecificCause().getMessage().contains("freezes entity retirement"));
    db.execute("rollback to savepoint entity_sql");
    db.execute("savepoint entity_sql");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "insert into atlas_test.hr_legal_entity_assignment(tenant,employee_id,episode_start,entity_code,effective_date,recorded_at,request_id,version_id) select tenant,employee_id,episode_start+1,entity_code,effective_date,recorded_at,?,version_id+999999 from atlas_test.hr_legal_entity_assignment",
                key()));
    db.execute("rollback to savepoint entity_sql");
  }
}
