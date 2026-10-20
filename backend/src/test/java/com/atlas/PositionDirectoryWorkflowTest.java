package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.adapter.out.persistence.*;
import com.atlas.modules.corehr.application.model.PositionDirectoryModels.*;
import com.atlas.modules.corehr.application.port.in.PositionDirectoryUseCases;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.PositionDirectoryService;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
class PositionDirectoryWorkflowTest {
  static final String SCHEMA =
      "atlas_bi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://localhost:5438/atlas?currentSchema=" + SCHEMA);
    r.add("spring.flyway.default-schema", () -> SCHEMA);
    r.add("spring.flyway.schemas", () -> SCHEMA);
  }

  @Autowired PositionDirectoryUseCases directory;
  @Autowired JdbcTemplate db;
  @Autowired JdbcClient client;

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  Query q(String search, String department, String timing, int page, int size, String direction) {
    return new Query(search, department, timing, page, size, direction);
  }

  void department(String tenant, String code, String name) {
    db.update(
        "insert into hr_department(tenant,code,name,cost_centre,effective_from,created_by,idempotency_key) values(?,?,?,'DEMO',date '1970-01-01','fixture',?)",
        tenant,
        code,
        name,
        UUID.randomUUID().toString());
  }

  void position(String tenant, String code, String title, String dept, LocalDate date) {
    db.update(
        "insert into hr_position(tenant,code,title,department_code,effective_from,created_by,idempotency_key) values(?,?,?,?,?,'fixture',?)",
        tenant,
        code,
        title,
        dept,
        date,
        UUID.randomUUID().toString());
  }

  @Test
  void completeCatalogueBeyond1000WithExactStartAndPlannedPages() {
    department("atlas", "BI-DEPT", "BI directory department");
    db.update(
        "insert into hr_position(tenant,code,title,department_code,effective_from,created_by,idempotency_key) select 'atlas','BI-P'||lpad(n::text,4,'0'),'BI directory '||lpad(n::text,4,'0'),'BI-DEPT',?::date+case when n%2=0 then 0 else 1 end,'fixture','bi-'||n from generate_series(0,1004) n",
        today());
    var seen = new HashSet<String>();
    for (int page = 0; page < 21; page++) {
      var result = directory.browse("sophia", q("BI directory", "", "ALL", page, 50, "asc"));
      assertEquals(1005, result.total());
      assertEquals(today(), result.evaluatedOn());
      assertTrue(result.items().size() <= 50);
      for (var p : result.items()) {
        assertTrue(seen.add(p.code()));
        assertEquals("BI directory department", p.departmentName());
        assertEquals(p.effectiveFrom().isAfter(today()) ? "PLANNED" : "CURRENT", p.timing());
      }
    }
    assertEquals(1005, seen.size());
    assertEquals(
        503,
        directory.browse("marcus", q("BI directory", "BI-DEPT", "CURRENT", 0, 50, "asc")).total());
    var planned =
        directory.browse("amelia", q("BI directory", "BI-DEPT", "PLANNED", 10, 50, "desc"));
    assertEquals(502, planned.total());
    assertEquals(2, planned.items().size());
    assertEquals("BI-P0003", planned.items().getFirst().code());
    assertEquals(
        0,
        directory
            .browse("sophia", q("BI directory", "BI-DEPT", "ALL", 100000, 50, "asc"))
            .items()
            .size());
  }

  @Test
  void literalSearchTiesDepartmentAndTenantBeforeCountAndMapping() {
    department("atlas", "BI-A", "Visible A");
    department("atlas", "BI-B", "Visible B");
    department("other", "BI-A", "Private other name");
    position("atlas", "BI-A1", "BI literal %_ Same", "BI-A", today());
    position("atlas", "BI-A2", "BI literal %_ Same", "BI-A", today().plusDays(1));
    position("atlas", "BI-B1", "BI literal %_ Same", "BI-B", today());
    position("other", "BI-A1", "BI literal %_ Same", "BI-A", today());
    var a = directory.browse("sophia", q("%_", "BI-A", "ALL", 0, 8, "desc"));
    assertEquals(2, a.total());
    assertEquals(List.of("BI-A1", "BI-A2"), a.items().stream().map(Item::code).toList());
    assertTrue(a.items().stream().allMatch(p -> p.departmentName().equals("Visible A")));
    assertEquals(3, directory.browse("marcus", q("BI LITERAL", "", "ALL", 0, 8, "asc")).total());
    assertEquals(1, directory.browse("outsider", q("%_", "BI-A", "ALL", 0, 8, "asc")).total());
    assertEquals(0, directory.browse("sophia", q("%_", "MISSING", "ALL", 0, 8, "asc")).total());
    assertEquals(0, directory.browse("sophia", q("' OR 1=1 --", "", "ALL", 0, 8, "asc")).total());
  }

  @Test
  void oneUtcDateAndCurrentMembershipResolvedInsideReadBoundary() {
    department("atlas", "BI-DATE", "Date department");
    department("other", "BI-DATE", "Other date department");
    position("atlas", "BI-DATE-P", "BI boundary", "BI-DATE", LocalDate.of(2028, 2, 29));
    position("atlas", "BI-DATE-F", "BI boundary", "BI-DATE", LocalDate.of(2028, 3, 1));
    position("other", "BI-DATE-O", "BI boundary", "BI-DATE", LocalDate.of(2028, 2, 29));
    var calls = new AtomicInteger();
    var clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneId.of("Pacific/Kiritimati");
          }

          public Clock withZone(ZoneId z) {
            return this;
          }

          public Instant instant() {
            return calls.getAndIncrement() == 0
                ? Instant.parse("2028-02-29T23:59:59.999999999Z")
                : Instant.parse("2028-03-01T00:00:00Z");
          }
        };
    var tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> work) {
            db.update(
                "update identity_membership set tenant='other',employee_id='E900' where subject='sophia'");
            return work.get();
          }
        };
    var service =
        new PositionDirectoryService(
            new JdbcPeopleStore(client), new JdbcPositionDirectoryStore(client), tx, clock);
    var result = service.browse("sophia", q("BI boundary", "BI-DATE", "CURRENT", 0, 8, "asc"));
    assertEquals(1, calls.get());
    assertEquals(LocalDate.of(2028, 2, 29), result.evaluatedOn());
    assertEquals(1, result.total());
    assertEquals("BI-DATE-O", result.items().getFirst().code());
    assertEquals("CURRENT", result.items().getFirst().timing());
    assertEquals("Other date department", result.items().getFirst().departmentName());
    assertThrows(
        ChangeRequest.Denied.class,
        () -> directory.browse("unknown-subject", q("", "", "ALL", 0, 8, "asc")));
  }

  @Test
  void domainPositionStartUsesInclusiveBusinessDate() {
    var date = LocalDate.of(2028, 2, 29);
    var p = new Position("BI-DOMAIN", "Synthetic", "BI-DEPT", date);
    assertFalse(p.currentOn(date.minusDays(1)));
    assertTrue(p.currentOn(date));
    assertTrue(p.currentOn(date.plusDays(1)));
  }

  @Test
  void invalidQueryValuesNeverReachPersistence() {
    for (String timing : Arrays.asList(null, "", "current", "DROPPED"))
      assertThrows(IllegalArgumentException.class, () -> q("", "", timing, 0, 8, "asc"));
    for (int size : List.of(-1, 0, 51, Integer.MAX_VALUE))
      assertThrows(IllegalArgumentException.class, () -> q("", "", "ALL", 0, size, "asc"));
    for (int page : List.of(-1, 100001, Integer.MAX_VALUE))
      assertThrows(IllegalArgumentException.class, () -> q("", "", "ALL", page, 8, "asc"));
    for (String code : Arrays.asList(null, "bad code", "a", "x".repeat(31)))
      assertThrows(IllegalArgumentException.class, () -> q("", code, "ALL", 0, 8, "asc"));
    assertThrows(IllegalArgumentException.class, () -> q("x".repeat(121), "", "ALL", 0, 8, "asc"));
    assertThrows(IllegalArgumentException.class, () -> q(null, "", "ALL", 0, 8, "asc"));
    assertThrows(IllegalArgumentException.class, () -> q("", "", "ALL", 0, 8, "desc; drop table"));
  }
}
