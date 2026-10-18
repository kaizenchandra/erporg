package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore;
import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.port.in.PeopleUseCases;
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
class LegalNameWorkflowTest {
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;

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

  Submit command(String name, LocalDate date, long version) {
    return new Submit(
        "E003",
        "legalName",
        name,
        date,
        "Employee supplied recorded legal name",
        version,
        UUID.randomUUID().toString());
  }

  @Test
  void independentApprovalRecordsPrivateNameWithoutChangingPublicIdentity() {
    var before = people.profile(self(), "E003");
    assertEquals("", before.legalName());
    var c = command("Zoë 李 Synthetic", today(), before.version());
    var r = people.submit(self(), c, "legal-submit");
    assertEquals(r.id(), people.submit(self(), c, "replay").id());
    assertEquals("", people.profile(self(), "E003").legalName());
    people.decide(
        hr(),
        r.id(),
        new Decide("APPROVED", "Recorded supplied name; no document verification"),
        "legal-approved");
    var after = people.profile(self(), "E003");
    assertEquals(c.newValue(), after.legalName());
    assertEquals(c.newValue(), people.profile(hr(), "E003").legalName());
    assertNull(people.profile(manager(), "E003").legalName());
    assertTrue(
        people
            .directory(manager(), new DirectoryQuery("E003", "", "", 0, 12, "asc"))
            .items()
            .stream()
            .allMatch(e -> e.legalName() == null));
    assertEquals(
        0,
        people
            .directory(manager(), new DirectoryQuery(c.newValue(), "", "", 0, 12, "asc"))
            .total());
    assertEquals(before.name(), after.name());
    assertEquals(before.email(), after.email());
    assertEquals(before.id(), after.id());
    assertEquals(before.name(), people.actor("sophia").name());
    assertTrue(
        people.history(self(), "E003").stream()
            .anyMatch(
                a ->
                    a.field().equals("legalName")
                        && a.oldValue().isEmpty()
                        && a.newValue().equals(c.newValue())
                        && a.actor().equals("amelia")));
    assertTrue(
        people.history(manager(), "E003").stream().noneMatch(a -> a.field().equals("legalName")));
  }

  @Test
  void futureAndRejectedNamesDoNotApplyEarly() {
    var r =
        people.submit(
            self(), command("Future Synthetic Legal Name", today().plusDays(5), 0), "future");
    people.decide(hr(), r.id(), new Decide("APPROVED", "Future supplied value"), "future-review");
    assertEquals("", people.profile(self(), "E003").legalName());
    var store = new JdbcPeopleStore(JdbcClient.create(db.getDataSource()));
    assertEquals("", store.employee(self(), "E003", today().plusDays(4), false).legalName());
    assertEquals(
        "Future Synthetic Legal Name",
        store.employee(self(), "E003", today().plusDays(5), false).legalName());
    var no = people.submit(self(), command("Rejected Synthetic Name", today(), 1), "reject");
    people.decide(hr(), no.id(), new Decide("REJECTED", "Request needs clarification"), "rejected");
    assertEquals("", people.profile(self(), "E003").legalName());
  }

  @Test
  void deniesManagerTenantAndSelfReviewAndRequiresFreshApprovalSnapshot() {
    var c = command("Supplied Legal Name", today(), 0);
    assertThrows(ChangeRequest.Denied.class, () -> people.submit(manager(), c, "manager"));
    assertThrows(
        ChangeRequest.Denied.class, () -> people.submit(people.actor("outsider"), c, "tenant"));
    var r = people.submit(self(), c, "self");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.decide(self(), r.id(), new Decide("APPROVED", "Self"), "self-review"));
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            people.decide(manager(), r.id(), new Decide("APPROVED", "Manager"), "manager-review"));
    assertTrue(people.requests(manager()).stream().noneMatch(q -> q.id().equals(r.id())));
    var other =
        people.submit(
            self(),
            new Submit(
                "E003",
                "phone",
                "+1 202 555 0198",
                today(),
                "Concurrent contact change",
                0,
                UUID.randomUUID().toString()),
            "other");
    people.decide(
        hr(), other.id(), new Decide("APPROVED", "Independent contact review"), "other-approved");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> people.decide(hr(), r.id(), new Decide("APPROVED", "Stale"), "stale"));
    people.decide(hr(), r.id(), new Decide("REJECTED", "Refresh and submit again"), "rejected");
    assertEquals("", people.profile(self(), "E003").legalName());
  }

  @Test
  void databaseRejectsControlTextEvenWhenApplicationValidationIsBypassed() {
    var r = people.submit(self(), command("Valid Supplied Name", today(), 0), "valid");
    db.execute("savepoint legal_shape_probe");
    var error =
        assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                db.update(
                    "insert into hr_request select (jsonb_populate_record(null::hr_request,to_jsonb(r)||jsonb_build_object('id','legal-invalid-sql','idempotency_key','legal-invalid-sql-key','new_value',E'Invalid\\nName'))).* from hr_request r where r.id=?",
                    r.id()));
    assertTrue(error.getMostSpecificCause().getMessage().contains("legal_name_request_shape"));
    db.execute("rollback to savepoint legal_shape_probe");
  }
}
