package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.adapter.out.persistence.JdbcDepartmentDirectoryStore;
import com.atlas.modules.corehr.application.model.DepartmentDirectoryModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.DepartmentDirectoryService;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
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
class DepartmentDirectoryWorkflowTest {
  @Autowired DepartmentDirectoryUseCases directory;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;
  @Autowired JdbcClient client;

  Query q(
      String search,
      String timing,
      String relationship,
      String parent,
      int page,
      int size,
      String direction) {
    return new Query(search, timing, relationship, parent, page, size, direction);
  }

  void insert(String tenant, String code, String name, String parent, String date) {
    db.update(
        "insert into hr_department(tenant,code,name,parent_code,cost_centre,effective_from,created_by,idempotency_key) values(?,?,?,?,'DEMO',?::date,'fixture',?)",
        tenant,
        code,
        name,
        parent,
        date,
        UUID.randomUUID().toString());
  }

  @Test
  void fullDirectoryBeyondLegacyLimitAndParentOutsidePage() {
    insert("atlas", "BA-PARENT", "ZZZ directory parent", null, "2026-01-01");
    for (int i = 0; i < 1005; i++)
      insert(
          "atlas",
          "BA-" + String.format("%04d", i),
          "BA directory " + String.format("%04d", i),
          "BA-PARENT",
          "2026-01-01");
    insert("other", "BA-PARENT", "Other tenant parent", null, "2026-01-01");
    var actor = people.actor("sophia");
    var seen = new HashSet<String>();
    for (int page = 0; page < 21; page++) {
      var result = directory.browse(actor, q("BA directory", "ALL", "ALL", "", page, 50, "asc"));
      assertEquals(1005, result.total());
      for (var item : result.items()) {
        assertTrue(seen.add(item.code()));
        assertEquals("ZZZ directory parent", item.parentName());
        assertEquals("BA-PARENT", item.parentCode());
      }
    }
    assertEquals(1005, seen.size());
    assertEquals(
        "BA-1004",
        directory
            .browse(actor, q("BA-1004", "ALL", "ALL", "", 0, 1, "asc"))
            .items()
            .getFirst()
            .code());
    var children = directory.browse(actor, q("", "ALL", "ALL", "BA-PARENT", 0, 50, "desc"));
    assertEquals(1005, children.total());
    assertEquals("BA-1004", children.items().getFirst().code());
    var root =
        directory
            .browse(actor, q("ZZZ directory", "ALL", "ROOTS", "", 0, 1, "asc"))
            .items()
            .getFirst();
    assertEquals(1005, root.directChildren());
    assertNull(root.parentName());
    var outsider =
        directory.browse(
            people.actor("outsider"), q("Other tenant parent", "ALL", "ALL", "", 0, 8, "asc"));
    assertEquals(1, outsider.total());
    assertEquals(0, outsider.items().getFirst().directChildren());
  }

  @Test
  void injectedUtcBoundaryAndConsistentDate() {
    insert("atlas", "BA-TODAY", "BA boundary today", null, "2030-01-02");
    insert("atlas", "BA-NEXT", "BA boundary next", null, "2030-01-03");
    var pass =
        new UnitOfWork() {
          public <T> T run(Supplier<T> s) {
            return s.get();
          }
        };
    var service =
        new DepartmentDirectoryService(
            new JdbcDepartmentDirectoryStore(client),
            pass,
            Clock.fixed(
                Instant.parse("2030-01-02T23:59:59.999999999Z"), ZoneId.of("Pacific/Auckland")));
    var result =
        service.browse(people.actor("amelia"), q("BA boundary", "CURRENT", "ALL", "", 0, 8, "asc"));
    assertEquals(LocalDate.of(2030, 1, 2), result.evaluatedOn());
    assertEquals(1, result.total());
    assertEquals("BA-TODAY", result.items().getFirst().code());
    assertEquals("CURRENT", result.items().getFirst().timing());
    assertEquals(
        "BA-NEXT",
        service
            .browse(people.actor("amelia"), q("BA boundary", "PLANNED", "ALL", "", 0, 8, "asc"))
            .items()
            .getFirst()
            .code());
    service =
        new DepartmentDirectoryService(
            new JdbcDepartmentDirectoryStore(client),
            pass,
            Clock.fixed(Instant.parse("2030-01-03T00:00:00Z"), ZoneOffset.UTC));
    assertEquals(
        2,
        service
            .browse(people.actor("amelia"), q("BA boundary", "CURRENT", "ALL", "", 0, 8, "asc"))
            .total());
  }

  @Test
  void queryBoundsAndLiteralSearch() {
    for (int size : new int[] {0, 51})
      assertThrows(IllegalArgumentException.class, () -> q("", "ALL", "ALL", "", 0, size, "asc"));
    assertThrows(
        IllegalArgumentException.class, () -> q("x".repeat(121), "ALL", "ALL", "", 0, 8, "asc"));
    assertThrows(
        IllegalArgumentException.class, () -> q("", "ALL", "ROOTS", "BA-PARENT", 0, 8, "asc"));
    assertThrows(IllegalArgumentException.class, () -> q("", "ALL", "ALL", "", -1, 8, "asc"));
    assertThrows(IllegalArgumentException.class, () -> q("", "BAD", "ALL", "", 0, 8, "asc"));
    assertThrows(IllegalArgumentException.class, () -> q("", "ALL", "ALL", "bad", 0, 8, "asc"));
    assertThrows(IllegalArgumentException.class, () -> q("", "ALL", "ALL", "", 0, 8, "bad"));
    assertEquals(
        0,
        directory.browse(people.actor("amelia"), q("%'_", "ALL", "ALL", "", 0, 8, "asc")).total());
  }
}
