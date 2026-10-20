package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.ReferenceHistoryModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.domain.model.*;
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
class ReferenceHistoryWorkflowTest {
  @Autowired ReferenceHistoryUseCases history;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;

  Query q(String kind, String code, String order, int page, int size, String boundary) {
    return new Query(kind, code, order, page, size, boundary);
  }

  void fixture(int count) {
    db.update(
        "insert into hr_department(tenant,code,name,cost_centre,effective_from,created_by,idempotency_key) select 'atlas','BC-'||lpad(g::text,4,'0'),'Synthetic BC department '||g,'DEMO-BC',date '2020-01-01','fixture','bc-key-'||g from generate_series(1,?)g",
        count);
    db.update(
        "insert into hr_department_audit(tenant,code,actor,reason,recorded_at,effective_from,correlation_id) select 'atlas','BC-'||lpad(g::text,4,'0'),'fixture','Synthetic BC tied-time creation',timestamptz '2026-01-01 00:00:00Z',date '2020-01-01','bc-fixture' from generate_series(1,?)g",
        count);
    db.update(
        "insert into hr_position(tenant,code,title,department_code,effective_from,created_by,idempotency_key) select 'atlas','BC-P-'||lpad(g::text,4,'0'),'Synthetic BC position '||g,'BC-0001',date '2020-01-01','fixture','bc-position-'||g from generate_series(1,?)g",
        count);
    db.update(
        "insert into hr_position_audit(tenant,code,actor,reason,recorded_at,effective_from,correlation_id) select 'atlas','BC-P-'||lpad(g::text,4,'0'),'fixture','Synthetic BC tied-time creation',timestamptz '2026-01-01 00:00:00Z',date '2020-01-01','bc-position-fixture' from generate_series(1,?)g",
        count);
  }

  List<Item> all(String kind, String order) {
    var rows = new ArrayList<Item>();
    String through = null;
    for (int page = 0; ; page++) {
      var result = history.history(people.actor("amelia"), q(kind, "", order, page, 25, through));
      through = result.throughId();
      rows.addAll(result.items());
      if (rows.size() >= result.total()) break;
      assertFalse(result.items().isEmpty());
    }
    return rows;
  }

  @Test
  void bothOwnedJournalsBeyond100AndTiedOrder() {
    fixture(127);
    for (String kind : List.of("DEPARTMENT", "POSITION")) {
      var asc = all(kind, "asc");
      var desc = all(kind, "desc");
      assertTrue(asc.size() >= 127);
      assertEquals(asc.size(), asc.stream().map(Item::id).distinct().count());
      Collections.reverse(asc);
      assertEquals(asc, desc);
      String code = kind.equals("DEPARTMENT") ? "BC-0001" : "BC-P-0001";
      var filtered = history.history(people.actor("olivia"), q(kind, code, "asc", 0, 1, null));
      assertEquals(1, filtered.total());
      assertEquals(code, filtered.items().getFirst().code());
      assertEquals(
          "bc" + (kind.equals("DEPARTMENT") ? "" : "-position") + "-fixture",
          filtered.items().getFirst().correlationId());
      assertEquals(
          0,
          history
              .history(people.actor("amelia"), q(kind, "NOT-FOUND", "desc", 0, 10, null))
              .total());
    }
  }

  @Test
  void higherIdsExcludedUntilRefreshAndEmptyBoundaryIsStable() {
    fixture(2);
    var hr = people.actor("amelia");
    var first = history.history(hr, q("DEPARTMENT", "BC-0001", "asc", 0, 1, null));
    db.update(
        "insert into hr_department_audit(tenant,code,actor,reason,recorded_at,effective_from,correlation_id) values('atlas','BC-0001','fixture','Later recording',now(),date '2020-01-01','bc-later')");
    assertEquals(
        1, history.history(hr, q("DEPARTMENT", "BC-0001", "asc", 0, 1, first.throughId())).total());
    assertEquals(2, history.history(hr, q("DEPARTMENT", "BC-0001", "asc", 0, 1, null)).total());
    assertEquals(0, history.history(hr, q("DEPARTMENT", "", "asc", 0, 10, "0")).total());
  }

  @Test
  void hrAndTenantRecheckedBeforeEverySelection() {
    fixture(1);
    var hr = people.actor("amelia");
    var first = history.history(hr, q("DEPARTMENT", "", "desc", 0, 1, null));
    for (String subject : List.of("sophia", "marcus", "outsider"))
      assertThrows(
          ChangeRequest.Denied.class,
          () ->
              history.history(
                  people.actor(subject), q("DEPARTMENT", "", "desc", 0, 1, first.throughId())));
    var other = new Actor("other-fixture", "other", "E900", "Other HR", "HR");
    assertFalse(
        history
            .history(other, q("DEPARTMENT", "", "desc", 0, 50, first.throughId()))
            .items()
            .stream()
            .anyMatch(i -> i.code().startsWith("BC-")));
    db.update("update identity_membership set role='EMPLOYEE' where subject='amelia'");
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            history.history(
                people.actor("amelia"), q("DEPARTMENT", "", "desc", 0, 1, first.throughId())));
  }

  @Test
  void exactQueryBounds() {
    for (String value : List.of("-1", "01", "1.5", "9223372036854775808", "x".repeat(100)))
      assertThrows(IllegalArgumentException.class, () -> q("DEPARTMENT", "", "desc", 0, 10, value));
    assertThrows(IllegalArgumentException.class, () -> q("DEPARTMENT", "", "desc", 1, 10, null));
    assertThrows(IllegalArgumentException.class, () -> q("BAD", "", "desc", 0, 10, null));
    assertThrows(IllegalArgumentException.class, () -> q("DEPARTMENT", "bad", "desc", 0, 10, null));
    assertThrows(IllegalArgumentException.class, () -> q("DEPARTMENT", "", "unsafe", 0, 10, null));
    assertThrows(IllegalArgumentException.class, () -> q("DEPARTMENT", "", "desc", 0, 51, null));
    assertThrows(IllegalArgumentException.class, () -> q("DEPARTMENT", "", "desc", -1, 1, null));
  }
}
