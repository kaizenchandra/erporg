package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.model.EmployeeHistoryModels.*;
import com.atlas.modules.corehr.application.port.in.*;
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
class EmployeeHistoryWorkflowTest {
  @Autowired EmployeeHistoryUseCases history;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;

  Actor hr() {
    return people.actor("amelia");
  }

  Actor self() {
    return people.actor("sophia");
  }

  Query q(String field, String action, String order, int size, Cursor cursor) {
    return new Query(field, action, order, size, cursor);
  }

  void fixture(int rows) {
    // Rollback-only real PostgreSQL fixtures, exact tied recording instants and immutable audit.
    for (int i = 0; i < rows; i++) {
      String id = UUID.randomUUID().toString();
      String field = i % 2 == 0 ? "birthDate" : "phone";
      db.update(
          "insert into hr_request(id,tenant,employee_id,field,old_value,new_value,effective_date,reason,status,requested_by,employee_version,recorded_at,idempotency_key,decided_by,comment) values(?,'atlas','E003',?,'',?,current_date,'Synthetic pagination fixture','REJECTED','amelia',0,?::timestamptz,?,'olivia','Synthetic pagination fixture')",
          id,
          field,
          field.equals("birthDate") ? "1990-01-01" : "+44 7700 901234",
          "2026-01-01T12:00:00Z",
          UUID.randomUUID().toString());
      db.update(
          "insert into hr_audit(tenant,employee_id,request_id,actor,action,field,old_value,new_value,reason,recorded_at,correlation_id) values('atlas','E003',?,'olivia','REJECTED',?,'',?,'Synthetic pagination fixture',?::timestamptz,'history-fixture')",
          id,
          field,
          field.equals("birthDate") ? "1990-01-01" : "+44 7700 901234",
          "2026-01-01T12:00:00Z");
    }
  }

  List<Item> all(Actor a, Query query) {
    var out = new ArrayList<Item>();
    Cursor cursor = null;
    do {
      var page =
          history.history(
              a, "E003", q(query.field(), query.action(), query.order(), query.size(), cursor));
      out.addAll(page.items());
      cursor = page.next();
    } while (cursor != null);
    return out;
  }

  @Test
  void reachesAllRowsBeyondHundredWithTiedTimesInBothDirections() {
    fixture(127);
    var desc = all(self(), q("ALL", "REJECTED", "desc", 7, null));
    var asc = all(hr(), q("ALL", "REJECTED", "asc", 11, null));
    assertEquals(127, desc.size());
    assertEquals(127, new HashSet<>(desc.stream().map(Item::id).toList()).size());
    var reverse = new ArrayList<>(desc);
    Collections.reverse(reverse);
    assertEquals(reverse, asc);
    assertEquals(64, all(self(), q("birthDate", "REJECTED", "desc", 5, null)).size());
    assertTrue(all(self(), q("birthDate", "APPROVED", "desc", 5, null)).isEmpty());
  }

  @Test
  void managerFiltersBeforeCountAndPagingAndScopeIsRechecked() {
    fixture(127);
    var manager = people.actor("marcus");
    var page = history.history(manager, "E003", q("ALL", "REJECTED", "desc", 10, null));
    assertEquals(63, page.total());
    assertEquals(10, page.items().size());
    assertFalse(page.fields().contains("birthDate"));
    assertEquals(63, all(manager, q("ALL", "REJECTED", "desc", 10, null)).size());
    assertThrows(
        ChangeRequest.Denied.class,
        () -> history.history(manager, "E003", q("birthDate", "ALL", "desc", 10, null)));
    for (var actor :
        List.of(
            people.actor("outsider"),
            new Actor("history-unrelated", "atlas", "E008", "Unrelated", "EMPLOYEE")))
      assertThrows(
          ChangeRequest.Denied.class,
          () -> history.history(actor, "E003", q("ALL", "ALL", "desc", 10, null)));
    db.update("update hr_employee set manager_id='E001' where tenant='atlas' and id='E003'");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> history.history(manager, "E003", q("ALL", "REJECTED", "desc", 10, page.next())));
  }

  @Test
  void cursorCannotBeReusedForDifferentIdentityRecordFiltersOrRole() {
    fixture(20);
    var page = history.history(hr(), "E003", q("ALL", "REJECTED", "desc", 3, null));
    var cursor = page.next();
    assertNotNull(cursor);
    assertThrows(
        ChangeRequest.Validation.class,
        () -> history.history(self(), "E003", q("ALL", "REJECTED", "desc", 3, cursor)));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> history.history(hr(), "E004", q("ALL", "REJECTED", "desc", 3, cursor)));
    for (var query :
        List.of(
            q("phone", "REJECTED", "desc", 3, cursor),
            q("ALL", "ALL", "desc", 3, cursor),
            q("ALL", "REJECTED", "asc", 3, cursor),
            q("ALL", "REJECTED", "desc", 4, cursor)))
      assertThrows(ChangeRequest.Validation.class, () -> history.history(hr(), "E003", query));
    var forged =
        new Cursor(
            cursor.throughId(),
            cursor.beforeId(),
            cursor.beforeRecordedAt().minusSeconds(1),
            cursor.fingerprint());
    assertThrows(
        ChangeRequest.Validation.class,
        () -> history.history(hr(), "E003", q("ALL", "REJECTED", "desc", 3, forged)));
    var changedRole =
        new Actor(hr().subject(), hr().tenant(), hr().employeeId(), hr().name(), "EMPLOYEE");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> history.history(changedRole, "E003", q("ALL", "REJECTED", "desc", 3, cursor)));
    var selfPage = history.history(self(), "E003", q("ALL", "REJECTED", "desc", 3, null));
    db.update("update identity_membership set role='HR' where subject='sophia'");
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            history.history(
                people.actor("sophia"), "E003", q("ALL", "REJECTED", "desc", 3, selfPage.next())));
  }

  @Test
  void laterHigherIdWriteDoesNotShiftExistingPagesAndRefreshIncludesIt() {
    fixture(12);
    var first = history.history(self(), "E003", q("ALL", "REJECTED", "desc", 5, null));
    var expected = all(self(), q("ALL", "REJECTED", "desc", 5, null));
    fixture(1);
    var rows = new ArrayList<>(first.items());
    var cursor = first.next();
    while (cursor != null) {
      var next = history.history(self(), "E003", q("ALL", "REJECTED", "desc", 5, cursor));
      assertEquals(12, next.total());
      rows.addAll(next.items());
      cursor = next.next();
    }
    assertEquals(expected, rows);
    assertEquals(
        13, history.history(self(), "E003", q("ALL", "REJECTED", "desc", 5, null)).total());
  }

  @Test
  void realApprovedChangeHasExactProvenanceInNewReadModel() {
    var r =
        people.submit(
            self(),
            new Submit(
                "E003",
                "phone",
                "+44 7700 901234",
                LocalDate.now(ZoneOffset.UTC),
                "Actual approved history",
                people.profile(self(), "E003").version(),
                UUID.randomUUID().toString()),
            "history-submit");
    people.decide(
        hr(),
        r.id(),
        new Decide("APPROVED", "Actual independent history approval"),
        "history-decision");
    var result = history.history(self(), "E003", q("phone", "APPROVED", "desc", 10, null));
    assertEquals(1, result.total());
    var item = result.items().getFirst();
    assertEquals("+44 7700 901234", item.newValue());
    assertEquals("amelia", item.actor());
    assertEquals("history-decision", item.correlationId());
    assertEquals(r.effectiveDate(), item.effectiveDate());
    assertTrue(item.id().matches("[1-9][0-9]*"));
  }
}
