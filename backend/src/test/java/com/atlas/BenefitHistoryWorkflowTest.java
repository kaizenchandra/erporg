package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.benefits.application.model.PlanHistoryModels.*;
import com.atlas.modules.benefits.application.port.in.PlanHistoryUseCases;
import com.atlas.shared.domain.AccessDenied;
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
class BenefitHistoryWorkflowTest {
  @Autowired PlanHistoryUseCases history;
  @Autowired JdbcTemplate db;

  void fixture(int count) {
    db.update(
        "insert into benefit_plan(tenant,code,name,currency,monthly_contribution,minimum_service_days,effective_from,created_by,idempotency_key) select 'atlas','BE-'||lpad(g::text,4,'0'),'Synthetic BE plan '||g,'GBP',12.50,0,date '2020-01-01','fixture','be-plan-'||g from generate_series(1,?)g",
        count);
    db.update(
        "insert into benefit_plan_audit(tenant,code,actor,reason,recorded_at,effective_from,correlation_id) select 'atlas','BE-'||lpad(g::text,4,'0'),'fixture','Synthetic BE tied-time creation',timestamptz '2026-01-01 00:00:00Z',date '2020-01-01','be-fixture' from generate_series(1,?)g",
        count);
  }

  List<Item> all(String order) {
    var rows = new ArrayList<Item>();
    String boundary = null;
    for (int page = 0; ; page++) {
      var result = history.history("amelia", new Query("", order, page, 25, boundary));
      boundary = result.throughId();
      rows.addAll(result.items());
      if (rows.size() >= result.total()) break;
      assertFalse(result.items().isEmpty());
    }
    return rows;
  }

  @Test
  void fullTiedJournalBeyond100AndOriginalProvenance() {
    fixture(127);
    var asc = all("asc");
    var desc = all("desc");
    assertTrue(asc.size() >= 127);
    assertEquals(asc.size(), asc.stream().map(Item::id).distinct().count());
    var expected =
        db.queryForList(
            "select id::text from benefit_plan_audit where tenant='atlas' order by recorded_at,id",
            String.class);
    assertEquals(expected, asc.stream().map(Item::id).toList());
    Collections.reverse(asc);
    assertEquals(asc, desc);
    var exact = history.history("olivia", new Query("BE-0001", "asc", 0, 1, null));
    assertEquals(1, exact.total());
    assertEquals("be-fixture", exact.items().getFirst().correlationId());
    assertEquals("fixture", exact.items().getFirst().actor());
    assertEquals("2020-01-01", exact.items().getFirst().effectiveFrom().toString());
    assertEquals(0, history.history("amelia", new Query("ABSENT", "desc", 0, 10, null)).total());
  }

  @Test
  void higherIdsExcludedUntilRefresh() {
    fixture(1);
    var first = history.history("amelia", new Query("", "asc", 0, 1, null));
    db.update(
        "insert into benefit_plan(tenant,code,name,currency,monthly_contribution,minimum_service_days,effective_from,created_by,idempotency_key) values('atlas','BE-LATER','Synthetic later plan','GBP',0,0,date '2020-01-01','fixture','be-later-key')");
    db.update(
        "insert into benefit_plan_audit(tenant,code,actor,reason,recorded_at,effective_from,correlation_id) values('atlas','BE-LATER','fixture','Later creation',now(),date '2020-01-01','be-later')");
    assertEquals(
        first.total(),
        history.history("amelia", new Query("", "asc", 0, 50, first.throughId())).total());
    assertEquals(
        first.total() + 1, history.history("amelia", new Query("", "asc", 0, 50, null)).total());
    assertEquals(0, history.history("amelia", new Query("", "asc", 0, 10, "0")).total());
  }

  @Test
  void currentRoleAndTenantBeforeEveryRead() {
    fixture(2);
    var boundary = history.history("amelia", new Query("", "desc", 0, 1, null)).throughId();
    for (String subject : List.of("sophia", "marcus", "outsider"))
      assertThrows(
          AccessDenied.class,
          () -> history.history(subject, new Query("", "desc", 0, 1, boundary)));
    db.update("update identity_membership set role='HR' where subject='outsider'");
    var other = history.history("outsider", new Query("", "desc", 0, 50, boundary));
    assertEquals(0, other.total());
    assertTrue(other.items().isEmpty());
    db.update("update identity_membership set role='EMPLOYEE' where subject='amelia'");
    assertThrows(
        AccessDenied.class, () -> history.history("amelia", new Query("", "desc", 0, 1, boundary)));
  }

  @Test
  void exactBoundsAndCanonicalStringIds() {
    for (String value : List.of("-1", "01", "1.5", "9223372036854775808", "x".repeat(100)))
      assertThrows(IllegalArgumentException.class, () -> new Query("", "desc", 0, 10, value));
    assertThrows(IllegalArgumentException.class, () -> new Query("", "desc", 1, 10, null));
    assertThrows(IllegalArgumentException.class, () -> new Query(null, "desc", 0, 10, null));
    assertThrows(IllegalArgumentException.class, () -> new Query("lower", "desc", 0, 10, null));
    assertThrows(IllegalArgumentException.class, () -> new Query("", "unsafe", 0, 10, null));
    assertThrows(IllegalArgumentException.class, () -> new Query("", "desc", 0, 51, null));
    assertThrows(IllegalArgumentException.class, () -> new Query("", "desc", 0, 0, null));
    assertThrows(IllegalArgumentException.class, () -> new Query("", "desc", -1, 1, null));
    assertThrows(IllegalArgumentException.class, () -> new Query("", "desc", 100001, 1, null));
    assertEquals(
        "9223372036854775807", new Query("", "asc", 0, 1, "9223372036854775807").throughId());
  }
}
