package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.adapter.out.persistence.JdbcAdditionalContactsStore;
import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.ContactModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
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
class AdditionalContactsWorkflowTest {
  @Autowired AdditionalContactsUseCases contacts;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;

  String key() {
    return UUID.randomUUID().toString();
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  List<Input> rows(String suffix) {
    return List.of(
        new Input("Zoë Synthetic " + suffix, "Sibling", "+1 202 555 0101"),
        new Input("Jordan Synthetic " + suffix, "Friend", "+1 202 555 0102"));
  }

  Submit command(List<Input> rows, LocalDate date, long version) {
    return new Submit(
        rows, date, "Maintain additional emergency contacts", BigDecimal.valueOf(version), key());
  }

  Review review(String decision) {
    return new Review(decision, "Independent contact review", BigDecimal.ZERO, key());
  }

  @Test
  void atomicApprovalPreservesPrimaryAndHistoryAndStableReplays() {
    var before = people.profile(people.actor("sophia"), "E003");
    var c = command(rows("one"), today(), before.version());
    var r = contacts.submit("sophia", "E003", c, "submitted-correlation");
    assertEquals(r.id(), contacts.submit("sophia", "E003", c, "retry").id());
    assertTrue(contacts.context("sophia", "E003", new Query("ALL", 0, 8)).contacts().isEmpty());
    var decision = review("APPROVED");
    var approved = contacts.review("amelia", "E003", r.id(), decision, "decision-correlation");
    assertEquals(approved, contacts.review("amelia", "E003", r.id(), decision, "retry-decision"));
    assertEquals(r.id(), contacts.submit("sophia", "E003", c, "late-replay").id());
    var ctx = contacts.context("sophia", "E003", new Query("ALL", 0, 8));
    assertEquals(2, ctx.contacts().size());
    assertEquals(before.version() + 1, ctx.employeeVersion());
    assertEquals("decision-correlation", ctx.requests().items().getFirst().decisionCorrelationId());
    var after = people.profile(people.actor("sophia"), "E003");
    assertEquals(before.emergencyContactName(), after.emergencyContactName());
    assertEquals(before.emergencyContactPhone(), after.emergencyContactPhone());
    assertEquals(before.legalName(), after.legalName());
    assertEquals(2, db.queryForObject("select count(*) from hr_contact_audit", Integer.class));
    db.execute("set constraints all immediate");
  }

  @Test
  void futureRejectedAndApprovedRemovalRetainImmutableSnapshots() {
    var one = contacts.submit("sophia", "E003", command(rows("one"), today(), 0), "one");
    contacts.review("amelia", "E003", one.id(), review("APPROVED"), "one-reviewed");
    var future =
        contacts.submit(
            "sophia", "E003", command(rows("future"), today().plusDays(5), 1), "future");
    contacts.review("amelia", "E003", future.id(), review("APPROVED"), "future-reviewed");
    var store = new JdbcAdditionalContactsStore(JdbcClient.create(db.getDataSource()));
    assertEquals(
        "Zoë Synthetic one",
        store.current("atlas", "E003", today().plusDays(4)).contacts().getFirst().name());
    assertEquals(
        "Zoë Synthetic future",
        store.current("atlas", "E003", today().plusDays(5)).contacts().getFirst().name());
    var no = contacts.submit("sophia", "E003", command(rows("no"), today(), 2), "no");
    contacts.review("amelia", "E003", no.id(), review("REJECTED"), "no-reviewed");
    assertEquals(2, contacts.context("sophia", "E003", new Query("ALL", 0, 8)).employeeVersion());
    var removal =
        contacts.submit("sophia", "E003", command(List.of(), today().plusDays(10), 2), "remove");
    assertEquals(2, removal.oldContacts().size());
    contacts.review("amelia", "E003", removal.id(), review("APPROVED"), "removed");
    assertTrue(store.current("atlas", "E003", today().plusDays(10)).contacts().isEmpty());
    assertEquals(4, contacts.context("sophia", "E003", new Query("ALL", 0, 8)).requests().total());
    db.execute("set constraints all immediate");
  }

  @Test
  void privateScopeAndIndependentReviewerEmployeeAreEnforced() {
    for (var subject : List.of("marcus", "outsider")) {
      assertThrows(
          ChangeRequest.Denied.class,
          () -> contacts.context(subject, "E003", new Query("ALL", 0, 8)));
      assertThrows(
          ChangeRequest.Denied.class,
          () -> contacts.submit(subject, "E003", command(rows("no"), today(), 0), "forbidden"));
    }
    assertThrows(
        ChangeRequest.Denied.class,
        () -> contacts.context("sophia", "E001", new Query("ALL", 0, 8)));
    var r = contacts.submit("amelia", "E003", command(rows("hr"), today(), 0), "hr-request");
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) values('contact-hr-alias','atlas','E001','Synthetic alias','HR')");
    for (var subject : List.of("amelia", "contact-hr-alias", "sophia", "marcus"))
      assertThrows(
          ChangeRequest.Denied.class,
          () -> contacts.review(subject, "E003", r.id(), review("APPROVED"), "forbidden"));
    assertEquals(
        "APPROVED",
        contacts.review("olivia", "E003", r.id(), review("APPROVED"), "independent").status());
  }

  @Test
  void staleApprovalPendingAndConflictingKeysRequireRecovery() {
    var c = command(rows("old"), today(), 0);
    var r = contacts.submit("sophia", "E003", c, "pending");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> contacts.submit("sophia", "E003", command(rows("other"), today(), 0), "second"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            contacts.submit(
                "sophia",
                "E003",
                new Submit(
                    rows("different"),
                    c.effectiveDate(),
                    c.reason(),
                    c.employeeVersion(),
                    c.idempotencyKey()),
                "conflicting"));
    var change =
        people.submit(
            people.actor("sophia"),
            new Commands.Submit(
                "E003",
                "phone",
                "+1 202 555 0199",
                today(),
                "Concurrent personal change",
                0,
                key()),
            "changed");
    people.decide(
        people.actor("amelia"),
        change.id(),
        new Commands.Decide("APPROVED", "Independent"),
        "change-reviewed");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> contacts.review("amelia", "E003", r.id(), review("APPROVED"), "stale"));
    contacts.review("amelia", "E003", r.id(), review("REJECTED"), "recover");
    assertTrue(contacts.context("sophia", "E003", new Query("ALL", 0, 8)).contacts().isEmpty());
    assertEquals(1, people.profile(people.actor("sophia"), "E003").version());
  }

  @Test
  void databaseRejectsMutableOrIncompleteSnapshotsAndAudit() {
    var r = contacts.submit("sophia", "E003", command(rows("valid"), today(), 0), "complete");
    db.execute("set constraints all immediate");
    for (var table : List.of("hr_contact_item", "hr_contact_audit")) {
      db.execute("savepoint contact_immutable");
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> db.update("delete from " + table));
      db.execute("rollback to savepoint contact_immutable");
    }
    db.execute("set constraints all deferred");
    db.execute("savepoint incomplete_contact");
    db.update(
        "insert into hr_contact_request select (jsonb_populate_record(null::hr_contact_request,to_jsonb(r)||jsonb_build_object('id','incomplete-snapshot','employee_id','E004','requested_by','amelia','requester_employee_id','E001','employee_version',(select version from hr_employee where tenant='atlas' and id='E004'),'idempotency_key','incomplete-snapshot-key'))).* from hr_contact_request r where r.id=?",
        r.id());
    var error =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> db.execute("set constraints all immediate"));
    assertTrue(
        error
            .getMostSpecificCause()
            .getMessage()
            .contains("Contact snapshot/audit reconciliation failed"));
    db.execute("rollback to savepoint incomplete_contact");
  }

  @Test
  void historyPaginationRetainsAllRequestsWithoutTruncatingCurrentSnapshot() {
    for (int i = 0; i < 9; i++) {
      var r =
          contacts.submit("sophia", "E003", command(rows("attempt-" + i), today(), 0), "submitted");
      contacts.review("amelia", "E003", r.id(), review("REJECTED"), "rejected");
    }
    var second = contacts.context("sophia", "E003", new Query("ALL", 1, 8));
    assertEquals(9, second.requests().total());
    assertEquals(1, second.requests().items().size());
    assertTrue(second.contacts().isEmpty());
    assertEquals(
        0, contacts.context("sophia", "E003", new Query("SUBMITTED", 0, 8)).requests().total());
    db.execute("set constraints all immediate");
  }
}
