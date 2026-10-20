package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.adapter.out.persistence.*;
import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.DepartmentParentModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.DepartmentParentService;
import com.atlas.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("local")
class DepartmentParentWorkflowTest {
  static final String SCHEMA =
      "atlas_bj_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://localhost:5438/atlas?currentSchema=" + SCHEMA);
    r.add("spring.flyway.default-schema", () -> SCHEMA);
    r.add("spring.flyway.schemas", () -> SCHEMA);
  }

  @Autowired DepartmentParentUseCases parents;
  @Autowired OrganisationUseCases organisation;
  @Autowired DepartmentDirectoryUseCases directory;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;
  @Autowired JdbcClient client;
  @Autowired PlatformTransactionManager manager;

  String key() {
    return UUID.randomUUID().toString();
  }

  String prefix() {
    return "BJ-" + key().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  String root(String code, String parent, LocalDate date) {
    organisation.create(
        people.actor("amelia"),
        new CreateDepartment(
            code, code, parent, "DEMO-BJ", date, "Synthetic hierarchy fixture", key()),
        "bj-create");
    return code;
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  Command c(long version, String parent, String reason, String key) {
    return new Command(BigDecimal.valueOf(version), parent, today(), reason, key);
  }

  Command c(long version, String parent) {
    return c(version, parent, "Synthetic parent correction", key());
  }

  DepartmentDirectoryModels.Page browse(String search, String relation, String parent) {
    return directory.browse(
        people.actor("sophia"),
        new DepartmentDirectoryModels.Query(search, "ALL", relation, parent, 0, 50, "asc"));
  }

  UnitOfWork unit(TransactionTemplate tx) {
    return new UnitOfWork() {
      public <T> T run(Supplier<T> work) {
        return tx.execute(s -> work.get());
      }
    };
  }

  DepartmentParentUseCases at(Instant instant) {
    var read = new TransactionTemplate(manager);
    read.setReadOnly(true);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    return new DepartmentParentService(
        new JdbcPeopleStore(client),
        new JdbcDepartmentParentStore(client),
        unit(read),
        unit(new TransactionTemplate(manager)),
        Clock.fixed(instant, ZoneId.of("Pacific/Kiritimati")));
  }

  @Test
  void correctionAndRootResolveInAllCurrentReferenceProjectionsWithoutChangingEmployment() {
    String p = prefix(),
        a = root(p + "-A", null, today()),
        b = root(p + "-B", null, today()),
        child = root(p + "-C", a, today());
    String before =
        db.queryForObject(
            "select md5(string_agg(row_to_json(e)::text,',' order by id)) from hr_employee e",
            String.class);
    var result = parents.correct("amelia", child, c(0, b), "bj-correct");
    assertEquals(a, result.oldParentCode());
    assertEquals(b, result.parentCode());
    assertEquals(1, result.version());
    assertEquals(today(), result.effectiveDate());
    assertEquals("bj-correct", result.correlationId());
    assertEquals(b, browse(child, "ALL", "").items().getFirst().parentCode());
    assertEquals(b, browse(child, "ALL", "").items().getFirst().parentName());
    assertEquals(1, browse("", "ALL", b).total());
    assertEquals(0, browse("", "ALL", a).total());
    assertEquals(1, browse(b, "ALL", "").items().getFirst().directChildren());
    assertEquals(
        b,
        organisation.departments(people.actor("sophia")).stream()
            .filter(d -> d.code().equals(child))
            .findFirst()
            .orElseThrow()
            .parentCode());
    parents.correct("olivia", child, c(1, null), "bj-root");
    assertNull(parents.context("amelia", child, 0, 10).department().parentCode());
    assertEquals(3, browse(p, "ROOTS", "").total());
    assertEquals(2, parents.context("amelia", child, 0, 10).total());
    assertEquals(
        a,
        db.queryForObject(
            "select parent_code from hr_department where tenant='atlas' and code=?",
            String.class,
            child));
    assertEquals(
        before,
        db.queryForObject(
            "select md5(string_agg(row_to_json(e)::text,',' order by id)) from hr_employee e",
            String.class));
  }

  @Test
  @Transactional
  void historicalDatesAndReplayPreserveOriginalDeclarationAndMicrosecondResult() {
    String p = prefix(),
        a = root(p + "-A", null, today()),
        b = root(p + "-B", null, today()),
        child = root(p + "-C", a, today());
    var time = today().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).plusNanos(123456789);
    var service = at(time);
    var input =
        new Command(
            BigDecimal.ZERO, b, today().plusDays(1), "Original date-effective correction", key());
    var first = service.correct("amelia", child, input, "bj-temporal");
    assertEquals(123456000, first.recordedAt().getNano());
    assertEquals(
        a,
        db.queryForObject(
            "select hr_department_parent('atlas',?,?::date)", String.class, child, today()));
    assertEquals(
        b,
        db.queryForObject(
            "select hr_department_parent('atlas',?,?::date)",
            String.class,
            child,
            today().plusDays(1)));
    assertEquals(
        first,
        at(time.plusSeconds(86400)).correct("amelia", child, input, "ignored-replay-correlation"));
    assertThrows(
        CommandConflict.class, () -> parents.correct("amelia", a, c(0, b), "clock-regression"));
    assertThrows(CommandConflict.class, () -> service.correct("amelia", child, c(0, a), "stale"));
    assertThrows(
        CommandConflict.class,
        () ->
            service.correct(
                "amelia", child, c(0, a, input.reason(), input.idempotencyKey()), "different"));
  }

  @Test
  void accessValidationNoopCycleAndUnavailableParentAreNonMutating() {
    String p = prefix(),
        a = root(p + "-A", null, today()),
        b = root(p + "-B", a, today()),
        future = root(p + "-F", null, today().plusDays(1));
    assertThrows(AccessDenied.class, () -> parents.context("sophia", a, 0, 10));
    assertThrows(AccessDenied.class, () -> parents.context("outsider", a, 0, 10));
    assertThrows(AccessDenied.class, () -> parents.correct("marcus", a, c(0, b), "denied"));
    assertThrows(InputValidation.class, () -> parents.correct("amelia", a, c(0, b), "cycle"));
    assertThrows(InputValidation.class, () -> parents.correct("amelia", a, c(0, a), "self"));
    assertThrows(InputValidation.class, () -> parents.correct("amelia", a, c(0, null), "noop"));
    assertThrows(
        InputValidation.class, () -> parents.correct("amelia", a, c(0, future), "future-parent"));
    assertThrows(
        InputValidation.class, () -> parents.correct("amelia", future, c(0, a), "future-target"));
    assertThrows(
        InputValidation.class, () -> parents.correct("amelia", a, c(0, "MISSING"), "missing"));
    for (BigDecimal version :
        Arrays.asList(
            null,
            new BigDecimal("0.5"),
            new BigDecimal("-1"),
            new BigDecimal("9223372036854775808")))
      assertThrows(
          InputValidation.class,
          () ->
              parents.correct(
                  "amelia",
                  a,
                  new Command(version, b, today(), "Synthetic invalid version", key()),
                  "invalid"));
    assertThrows(
        InputValidation.class, () -> parents.correct("amelia", a, c(0, b, "", key()), "reason"));
    assertThrows(IllegalArgumentException.class, () -> parents.context("amelia", a, -1, 10));
    assertThrows(IllegalArgumentException.class, () -> parents.context("amelia", a, 0, 51));
    db.update(
        "insert into identity_membership values(?, 'atlas','E001','Synthetic current role','HR')",
        p + "-actor");
    db.update("update identity_membership set role='EMPLOYEE' where subject=?", p + "-actor");
    assertThrows(AccessDenied.class, () -> parents.correct(p + "-actor", a, c(0, b), "demoted"));
    db.update(
        "update identity_membership set tenant='other',employee_id='E900',role='HR' where subject=?",
        p + "-actor");
    assertThrows(AccessDenied.class, () -> parents.context(p + "-actor", a, 0, 10));
    assertEquals(0, parents.context("amelia", a, 0, 10).total());
  }

  @Test
  void completeHistoryBeyondOnePageAndCurrentReplayRemainStable() {
    String p = prefix(),
        a = root(p + "-A", null, today()),
        b = root(p + "-B", null, today()),
        child = root(p + "-C", null, today());
    for (int i = 0; i < 55; i++)
      parents.correct("amelia", child, c(i, i % 2 == 0 ? a : b), "bj-history-" + i);
    var first = parents.context("amelia", child, 0, 50);
    var last = parents.context("amelia", child, 1, 50);
    assertEquals(55, first.total());
    assertEquals(50, first.items().size());
    assertEquals(5, last.items().size());
    assertEquals(55, first.department().version());
    assertEquals(55, first.items().getFirst().version());
    assertEquals(1, last.items().getLast().version());
    assertTrue(
        first.items().stream()
            .allMatch(
                x -> x.actor().equals("amelia") && x.correlationId().startsWith("bj-history-")));
  }

  @Test
  void concurrentCorrectionsHaveOneWinnerAndOneImmutableEvent() throws Exception {
    String p = prefix(),
        a = root(p + "-A", null, today()),
        b = root(p + "-B", null, today()),
        child = root(p + "-C", null, today());
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var tasks =
          List.of(
              pool.submit(() -> race(start, "amelia", child, a)),
              pool.submit(() -> race(start, "olivia", child, b)));
      start.countDown();
      var outcomes = new ArrayList<Integer>();
      for (var task : tasks) outcomes.add(task.get(15, TimeUnit.SECONDS));
      Collections.sort(outcomes);
      assertEquals(List.of(200, 409), outcomes);
      assertEquals(1, parents.context("amelia", child, 0, 10).total());
    } finally {
      pool.shutdownNow();
    }
  }

  int race(CountDownLatch latch, String subject, String code, String parent) throws Exception {
    latch.await();
    try {
      parents.correct(subject, code, c(0, parent), "bj-race");
      return 200;
    } catch (CommandConflict e) {
      return 409;
    }
  }

  void rejectedSql(String sql, Object... args) {
    assertThrows(
        RuntimeException.class,
        () -> new TransactionTemplate(manager).executeWithoutResult(s -> db.update(sql, args)));
  }

  @Test
  void databaseRejectsForgedStateCycleChronologyAndMutation() {
    String p = prefix(),
        a = root(p + "-A", null, today()),
        b = root(p + "-B", a, today()),
        future = root(p + "-F", null, today().plusDays(1));
    String insert =
        "insert into hr_department_parent_change(tenant,code,version,old_parent_code,parent_code,effective_date,recorded_at,actor,reason,idempotency_key,correlation_id) values('atlas',?,?,?, ?, (now() at time zone 'UTC')::date,now(),'amelia','Synthetic SQL guard',?,'bj-sql')";
    rejectedSql(insert, a, 1L, null, b, key());
    rejectedSql(insert, a, 2L, null, b, key());
    rejectedSql(insert, b, 1L, null, null, key());
    rejectedSql(insert, b, 1L, a, future, key());
    rejectedSql("update hr_department set parent_code=null where tenant='atlas' and code=?", b);
    rejectedSql("delete from hr_department where tenant='atlas' and code=?", b);
    var saved = parents.correct("amelia", b, c(0, null), "bj-real");
    rejectedSql(
        "update hr_department_parent_change set reason='Altered' where tenant='atlas' and code=?",
        b);
    rejectedSql("delete from hr_department_parent_change where tenant='atlas' and code=?", b);
    rejectedSql("truncate hr_department_parent_change");
    rejectedSql("truncate hr_department cascade");
    assertEquals(saved, parents.context("amelia", b, 0, 10).items().getFirst());
    assertEquals(0, parents.context("amelia", a, 0, 10).total());
  }
}
