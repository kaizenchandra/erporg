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
class PostalAddressWorkflowTest {
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;

  Actor self() {
    return people.actor("sophia");
  }

  Actor hr() {
    return people.actor("amelia");
  }

  Actor manager() {
    return people.actor("marcus");
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  PostalAddressModels.Submit command(LocalDate date, long version) {
    return new PostalAddressModels.Submit(
        new PostalAddressModels.Input(
            "18 Rue Étoile 李", "Unit 3", "Montréal", "Québec", "H2X 1Y4", "CA"),
        date,
        "Employee supplied postal address",
        version,
        UUID.randomUUID().toString());
  }

  void approve(ChangeRequest r) {
    people.decide(
        hr(),
        r.id(),
        new Decide("APPROVED", "Independent private address review"),
        "postal-decision");
  }

  @Test
  void privateApprovalReplayAndLegacyRemovalShareOneAuthoritativeStream() {
    var initial = people.postalAddress(self(), "E003");
    assertNull(initial.address());
    assertNotNull(initial.legacyAddress());
    assertTrue(initial.countries().stream().anyMatch(c -> c.code().equals("CA")));
    var c = command(today(), initial.employeeVersion());
    var r = people.submitPostalAddress(self(), "E003", c, "postal-submit");
    assertEquals(r.id(), people.submitPostalAddress(self(), "E003", c, "retry").id());
    assertNull(people.postalAddress(self(), "E003").address());
    approve(r);
    var current = people.postalAddress(self(), "E003");
    assertEquals("18 Rue Étoile 李", current.address().line1());
    assertNull(current.legacyAddress());
    assertEquals(current.address(), people.postalAddress(hr(), "E003").address());
    assertEquals(r.id(), people.submitPostalAddress(self(), "E003", c, "late-retry").id());
    assertNull(people.profile(manager(), "E003").address());
    assertTrue(
        people.history(manager(), "E003").stream().noneMatch(a -> a.field().equals("address")));
    assertTrue(
        people.history(self(), "E003").stream()
            .anyMatch(
                a ->
                    a.newValue().equals(current.address().canonicalValue())
                        && a.actor().equals("amelia")));
    var legacy =
        people.submit(
            self(),
            new Submit(
                "E003",
                "address",
                "Replacement free text",
                today(),
                "Employee free-text replacement",
                current.employeeVersion(),
                UUID.randomUUID().toString()),
            "legacy");
    approve(legacy);
    assertEquals("Replacement free text", people.postalAddress(self(), "E003").legacyAddress());
    var removal =
        people.submit(
            self(),
            new Submit(
                "E003",
                "address",
                "",
                today(),
                "Remove optional private address",
                people.profile(self(), "E003").version(),
                UUID.randomUUID().toString()),
            "remove");
    approve(removal);
    assertEquals("", people.postalAddress(self(), "E003").legacyAddress());
  }

  @Test
  void futureRejectedAndConcurrentApprovalRespectEffectiveDatesAndVersions() {
    var old = people.profile(self(), "E003").address();
    var c = command(today().plusDays(4), 0);
    var future = people.submitPostalAddress(self(), "E003", c, "future");
    approve(future);
    assertEquals(old, people.profile(self(), "E003").address());
    var store = new JdbcPeopleStore(JdbcClient.create(db.getDataSource()));
    assertEquals(old, store.employee(self(), "E003", today().plusDays(3), false).address());
    assertTrue(
        store
            .employee(self(), "E003", today().plusDays(4), false)
            .address()
            .startsWith(PostalAddress.PREFIX));
    var stale = people.submitPostalAddress(self(), "E003", command(today(), 1), "stale");
    var legacy =
        people.submit(
            self(),
            new Submit(
                "E003",
                "address",
                "Current legacy entry",
                today(),
                "Current replacement",
                1L,
                UUID.randomUUID().toString()),
            "current");
    assertThrows(ChangeRequest.Conflict.class, () -> approve(legacy));
    assertThrows(ChangeRequest.Conflict.class, () -> approve(stale));
    people.decide(hr(), stale.id(), new Decide("REJECTED", "Refresh stale request"), "reject");
    assertEquals(old, people.profile(self(), "E003").address());
    assertTrue(
        store
            .employee(self(), "E003", today().plusDays(4), false)
            .address()
            .startsWith(PostalAddress.PREFIX));
  }

  @Test
  void rejectsManagerOtherTenantSelfApprovalAndUnknownCountry() {
    assertThrows(ChangeRequest.Denied.class, () -> people.postalAddress(manager(), "E003"));
    assertThrows(
        ChangeRequest.Denied.class, () -> people.postalAddress(people.actor("outsider"), "E003"));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.submitPostalAddress(manager(), "E003", command(today(), 0), "manager"));
    var bad =
        new PostalAddressModels.Submit(
            new PostalAddressModels.Input("Street", "", "City", "", "", "ZZ"),
            today(),
            "Unknown country",
            0L,
            UUID.randomUUID().toString());
    assertThrows(
        ChangeRequest.Validation.class,
        () -> people.submitPostalAddress(self(), "E003", bad, "unknown"));
    var r = people.submitPostalAddress(self(), "E003", command(today(), 0), "self");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> people.decide(self(), r.id(), new Decide("APPROVED", "Self"), "self-review"));
  }

  @Test
  void databaseRejectsMalformedCanonicalAndOversizedLegacyWithoutFrameworkValidation() {
    var r = people.submitPostalAddress(self(), "E003", command(today(), 0), "valid");
    for (var value : List.of("@POSTAL/2\nx", "@POSTAL/1\nx\n\nCity\n\n\nus", "x".repeat(121))) {
      db.execute("savepoint postal_shape_probe");
      var e =
          assertThrows(
              org.springframework.dao.DataIntegrityViolationException.class,
              () ->
                  db.update(
                      "insert into hr_request select (jsonb_populate_record(null::hr_request,to_jsonb(r)||jsonb_build_object('id','postal-invalid-sql','idempotency_key','postal-invalid-sql-key','new_value',?))).* from hr_request r where r.id=?",
                      value,
                      r.id()));
      assertTrue(e.getMostSpecificCause().getMessage().contains("request_address_value_shape"));
      db.execute("rollback to savepoint postal_shape_probe");
    }
  }

  @Test
  void migrationPreflightRefusesAmbiguousLegacyPrefixWithoutRewritingIt() throws Exception {
    db.update(
        "update hr_employee set address='@POSTAL/legacy-collision' where tenant='atlas' and id='E003'");
    var migration =
        new org.springframework.core.io.ClassPathResource(
                "db/migration/V51__structured_private_postal_address.sql")
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    db.execute("savepoint postal_preflight_probe");
    var error =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> db.execute(migration.substring(0, migration.indexOf("alter table hr_request"))));
    assertTrue(
        error
            .getMostSpecificCause()
            .getMessage()
            .contains("Reserved postal address prefix collision"));
    db.execute("rollback to savepoint postal_preflight_probe");
    assertEquals(
        "@POSTAL/legacy-collision",
        db.queryForObject(
            "select address from hr_employee where tenant='atlas' and id='E003'", String.class));
  }
}
