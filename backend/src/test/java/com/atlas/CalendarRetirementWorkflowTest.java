package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.CalendarAmendmentModels;
import com.atlas.modules.corehr.application.model.CalendarModels;
import com.atlas.modules.corehr.application.model.CalendarRetirementModels.*;
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
class CalendarRetirementWorkflowTest {
  @Autowired CalendarRetirementUseCases retirements;
  @Autowired CalendarsUseCases calendars;
  @Autowired CalendarAmendmentsUseCases amendments;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;

  Actor hr() {
    return people.actor("amelia");
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  void base(String code) {
    calendars.create(
        hr(),
        new CalendarModels.Create(
            new CalendarModels.Input(
                code,
                "Company policy 李",
                "Europe/London",
                today().toString(),
                today().plusDays(40).toString(),
                List.of(
                    BigDecimal.ONE,
                    BigDecimal.valueOf(2),
                    BigDecimal.valueOf(3),
                    BigDecimal.valueOf(4),
                    BigDecimal.valueOf(5),
                    BigDecimal.valueOf(6),
                    BigDecimal.valueOf(7)),
                List.of()),
            "Company policy",
            key()),
        "base");
  }

  Retire command(int version, LocalDate date) {
    return new Retire(
        BigDecimal.valueOf(version), date.toString(), "Retire unassigned company calendar", key());
  }

  CalendarAmendmentModels.Amend amendment(int version, LocalDate date) {
    return new CalendarAmendmentModels.Amend(
        BigDecimal.valueOf(version),
        date.toString(),
        List.of(BigDecimal.ONE),
        List.of(),
        "Dated company replacement",
        key());
  }

  @Test
  void preservesOriginalAndEarlierPreviewAndReturnsStableReceipt() {
    base("RET-ONE");
    var original = calendars.detail(hr(), "RET-ONE");
    var c = command(0, today().plusDays(2));
    var e = retirements.retire(hr(), "RET-ONE", c, "retirement-correlation");
    assertEquals(e, retirements.retire(hr(), "RET-ONE", c, "retry"));
    assertEquals(original, calendars.detail(hr(), "RET-ONE"));
    assertEquals(
        "WORKING_DAY",
        calendars.preview(hr(), "RET-ONE", today().plusDays(1).toString()).classification());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> calendars.preview(hr(), "RET-ONE", today().plusDays(2).toString()));
    var context = amendments.context(hr(), "RET-ONE");
    assertEquals("SCHEDULED", context.retirementStatus());
    assertNull(context.draft());
    assertNull(context.retirementDraft());
    assertEquals(e, context.retirement());
    assertEquals("retirement-correlation", e.correlationId());
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            retirements.retire(
                hr(),
                "RET-ONE",
                new Retire(c.version(), c.effectiveDate(), "Different reason", c.idempotencyKey()),
                "conflict"));
  }

  @Test
  void recordedRetirementBlocksNewAmendmentsButKeepsOldReplay() {
    base("RET-AMEND");
    var a = amendment(0, today());
    var saved = amendments.amend(hr(), "RET-AMEND", a, "amended");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> retirements.retire(hr(), "RET-AMEND", command(0, today().plusDays(1)), "stale"));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> retirements.retire(hr(), "RET-AMEND", command(1, today()), "same-date"));
    retirements.retire(hr(), "RET-AMEND", command(1, today().plusDays(1)), "retire");
    assertEquals(saved, amendments.amend(hr(), "RET-AMEND", a, "late-amend-replay"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> amendments.amend(hr(), "RET-AMEND", amendment(1, today().plusDays(2)), "blocked"));
  }

  @Test
  void immediateRetirementChangesOnlyAvailabilityAndPageMetadata() {
    base("RET-NOW");
    retirements.retire(hr(), "RET-NOW", command(0, today()), "immediate");
    assertEquals(
        0, calendars.list(hr(), new CalendarModels.Query("RET-NOW", "CURRENT", 0, 10)).total());
    var page = calendars.list(hr(), new CalendarModels.Query("RET-NOW", "RETIRED", 0, 10));
    assertEquals(1, page.total());
    assertEquals(today(), page.retirementDates().get("RET-NOW"));
    assertEquals(
        1, calendars.list(hr(), new CalendarModels.Query("RET-NOW", "ALL", 0, 10)).total());
    assertEquals("RETIRED", amendments.context(hr(), "RET-NOW").retirementStatus());
    assertThrows(
        ChangeRequest.Conflict.class, () -> calendars.preview(hr(), "RET-NOW", today().toString()));
  }

  @Test
  void deniesRolesTenantAndFractionalVersion() {
    base("RET-SCOPE");
    for (var name : List.of("sophia", "marcus", "outsider"))
      assertThrows(
          ChangeRequest.Denied.class,
          () -> retirements.retire(people.actor(name), "RET-SCOPE", command(0, today()), "deny"));
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            retirements.retire(
                new Actor("other", "other", "OTHER", "Other HR", "HR"),
                "RET-SCOPE",
                command(0, today()),
                "tenant"));
    var c = command(0, today());
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            retirements.retire(
                hr(),
                "RET-SCOPE",
                new Retire(
                    new BigDecimal("0.5"), c.effectiveDate(), c.reason(), c.idempotencyKey()),
                "fraction"));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> retirements.retire(hr(), "RET-SCOPE", command(0, today().minusDays(1)), "past"));
  }

  @Test
  void qualifiedSqlGuardsAndImmutabilityRemainEffective() {
    base("RET-SQL");
    retirements.retire(hr(), "RET-SQL", command(0, today().plusDays(2)), "sql");
    db.execute("set local search_path=pg_catalog");
    for (var sql :
        List.of(
            "update atlas_test.hr_work_calendar_retirement set reason=reason",
            "delete from atlas_test.hr_work_calendar_retirement",
            "truncate atlas_test.hr_work_calendar_retirement",
            "insert into atlas_test.hr_work_calendar_revision(tenant,code,ordinal,effective_date,working_days,closed_dates,created_by,reason,recorded_at,correlation_id,idempotency_key) values('atlas','RET-SQL',1,(now() at time zone 'UTC')::date,array[1]::smallint[],array[]::date[],'amelia','Forbidden amendment',now(),'probe','retirement-sql-probe')")) {
      db.execute("savepoint retirement_probe");
      assertThrows(org.springframework.dao.DataAccessException.class, () -> db.execute(sql));
      db.execute("rollback to savepoint retirement_probe");
    }
    db.execute("set local search_path=atlas_test");
    assertEquals(0, amendments.context(hr(), "RET-SQL").history().size());
  }

  @Test
  void lateReplayAndExpiryDistinguishRetiredFromNaturalExpiry() {
    base("RET-LATE");
    base("RET-NATURAL");
    var c = command(0, today().plusDays(1));
    var saved = retirements.retire(hr(), "RET-LATE", c, "original");
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource());
    var store = new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarStore(jdbc);
    var history =
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarAmendmentStore(jdbc);
    var retired =
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarRetirementStore(jdbc);
    com.atlas.modules.corehr.application.port.out.UnitOfWork direct =
        new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> f) {
            return f.get();
          }
        };
    var clock =
        Clock.fixed(today().plusDays(45).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    var later =
        new com.atlas.modules.corehr.application.usecase.CalendarRetirementService(
            store, history, retired, direct, clock);
    assertEquals(saved, later.retire(hr(), "RET-LATE", c, "late-retry"));
    var view =
        new com.atlas.modules.corehr.application.usecase.CalendarsService(
            store, history, retired, direct, direct, clock);
    assertEquals(1, view.list(hr(), new CalendarModels.Query("RET-", "EXPIRED", 0, 10)).total());
    assertEquals(1, view.list(hr(), new CalendarModels.Query("RET-", "RETIRED", 0, 10)).total());
  }

  @Test
  void unsupportedWriterIsolationFailsClosedAndRollsBack() throws Exception {
    try (var connection = db.getDataSource().getConnection()) {
      connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute(
            "insert into atlas_test.hr_work_calendar(tenant,code,name,time_zone,effective_from,effective_until,working_days,closed_dates,created_by,reason,recorded_at,correlation_id,idempotency_key) values('atlas','RR-TEST','Isolation fixture','Europe/London',(now() at time zone 'UTC')::date,(now() at time zone 'UTC')::date+2,array[1]::smallint[],array[]::date[],'amelia','Isolation fixture',now(),'isolation-probe','isolation-base-key')");
        var error =
            assertThrows(
                java.sql.SQLException.class,
                () ->
                    statement.execute(
                        "insert into atlas_test.hr_work_calendar_retirement(tenant,code,calendar_version,effective_date,created_by,reason,recorded_at,correlation_id,idempotency_key) values('atlas','RR-TEST',0,(now() at time zone 'UTC')::date+1,'amelia','Retire fixture',now(),'isolation-probe','isolation-retire-key')"));
        assertEquals("P0001", error.getSQLState());
        assertTrue(error.getMessage().contains("supported isolation"));
      } finally {
        connection.rollback();
      }
    }
    assertEquals(
        0, calendars.list(hr(), new CalendarModels.Query("RR-TEST", "ALL", 0, 10)).total());
  }
}
