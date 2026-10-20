package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.CalendarAmendmentModels.*;
import com.atlas.modules.corehr.application.model.CalendarModels;
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
class CalendarAmendmentWorkflowTest {
  @Autowired CalendarAmendmentsUseCases amendments;
  @Autowired CalendarsUseCases calendars;
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
                List.of(BigDecimal.ONE),
                List.of()),
            "Initial policy",
            key()),
        "base");
  }

  Amend command(int version, LocalDate date) {
    return new Amend(
        BigDecimal.valueOf(version),
        date.toString(),
        List.of(BigDecimal.ONE, BigDecimal.valueOf(2)),
        List.of(date.toString()),
        "Replacement company policy",
        key());
  }

  @Test
  void datedPolicyRetainsBaseAndHistoryWithStableReplayAndFuturePreview() {
    base("AMEND-ONE");
    var original = calendars.detail(hr(), "AMEND-ONE");
    var c = command(0, today().plusDays(2));
    var e = amendments.amend(hr(), "AMEND-ONE", c, "amend-correlation");
    assertEquals(e, amendments.amend(hr(), "AMEND-ONE", c, "retry"));
    assertEquals(original, calendars.detail(hr(), "AMEND-ONE"));
    assertEquals(0, amendments.context(hr(), "AMEND-ONE").currentVersion());
    assertEquals(1, amendments.context(hr(), "AMEND-ONE").version());
    assertEquals(0, calendars.preview(hr(), "AMEND-ONE", today().toString()).revision());
    var preview = calendars.preview(hr(), "AMEND-ONE", today().plusDays(2).toString());
    assertEquals(1, preview.revision());
    assertEquals("COMPANY_CLOSURE", preview.classification());
    assertEquals("amend-correlation", e.correlationId());
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            amendments.amend(
                hr(),
                "AMEND-ONE",
                new Amend(
                    c.version(),
                    c.effectiveDate(),
                    c.workingDays(),
                    c.closedDates(),
                    "Different reason",
                    c.idempotencyKey()),
                "conflict"));
  }

  @Test
  void staleAndInvalidVersionsAndDatesDoNotPublish() {
    base("AMEND-VALID");
    amendments.amend(hr(), "AMEND-VALID", command(0, today().plusDays(2)), "first");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> amendments.amend(hr(), "AMEND-VALID", command(0, today().plusDays(3)), "stale"));
    for (var date : List.of(today().minusDays(1), today().plusDays(1), today().plusDays(41)))
      assertThrows(
          ChangeRequest.Validation.class,
          () -> amendments.amend(hr(), "AMEND-VALID", command(1, date), "date"));
    var c = command(1, today().plusDays(3));
    for (var version : List.of(new BigDecimal("1.5"), new BigDecimal("-1")))
      assertThrows(
          ChangeRequest.Validation.class,
          () ->
              amendments.amend(
                  hr(),
                  "AMEND-VALID",
                  new Amend(
                      version,
                      c.effectiveDate(),
                      c.workingDays(),
                      c.closedDates(),
                      c.reason(),
                      key()),
                  "version"));
    assertEquals(1, amendments.context(hr(), "AMEND-VALID").history().size());
  }

  @Test
  void scopeAndCrossCalendarKeyIsolationHold() {
    base("AMEND-SCOPE");
    base("AMEND-OTHER");
    var c = command(0, today());
    amendments.amend(hr(), "AMEND-SCOPE", c, "scope");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> amendments.amend(hr(), "AMEND-OTHER", c, "wrong-calendar"));
    for (var name : List.of("sophia", "marcus", "outsider")) {
      var actor = people.actor(name);
      assertThrows(ChangeRequest.Denied.class, () -> amendments.context(actor, "AMEND-SCOPE"));
      assertThrows(
          ChangeRequest.Denied.class,
          () -> amendments.amend(actor, "AMEND-SCOPE", command(1, today().plusDays(1)), "denied"));
    }
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            amendments.context(
                new Actor("other", "other", "OTHER", "Other HR", "HR"), "AMEND-SCOPE"));
  }

  @Test
  void capAndSqlGuardsPreserveCompleteHistory() {
    base("AMEND-CAP");
    for (int i = 0; i < 20; i++)
      amendments.amend(hr(), "AMEND-CAP", command(i, today().plusDays(i)), "amend");
    assertNull(amendments.context(hr(), "AMEND-CAP").draft());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> amendments.amend(hr(), "AMEND-CAP", command(20, today().plusDays(20)), "cap"));
    for (var sql :
        List.of(
            "update hr_work_calendar_revision set reason=reason",
            "delete from hr_work_calendar_revision",
            "truncate hr_work_calendar_revision",
            "insert into hr_work_calendar_revision select tenant,'AMEND-CAP',21,effective_date+1,working_days,closed_dates,created_by,reason,recorded_at,correlation_id,'amend-invalid-ordinal' from hr_work_calendar_revision where ordinal=20")) {
      db.execute("savepoint amendment_probe");
      assertThrows(org.springframework.dao.DataAccessException.class, () -> db.execute(sql));
      db.execute("rollback to savepoint amendment_probe");
    }
    assertEquals(20, amendments.context(hr(), "AMEND-CAP").history().size());
  }

  @Test
  void qualifiedSqlGuardsRejectSkippedSequenceAndPastClosures() {
    base("AMEND-SQL");
    amendments.amend(hr(), "AMEND-SQL", command(0, today()), "sql");
    db.execute("set local search_path=pg_catalog");
    for (var sql :
        List.of(
            "insert into atlas_test.hr_work_calendar_revision select tenant,code,3,effective_date+2,working_days,array[effective_date+2],created_by,reason,recorded_at,correlation_id,'amend-invalid-sequence' from atlas_test.hr_work_calendar_revision where ordinal=1",
            "insert into atlas_test.hr_work_calendar_revision select tenant,code,2,effective_date+1,working_days,array[effective_date],created_by,reason,recorded_at,correlation_id,'amend-invalid-closure' from atlas_test.hr_work_calendar_revision where ordinal=1")) {
      db.execute("savepoint amendment_sql");
      assertThrows(org.springframework.dao.DataAccessException.class, () -> db.execute(sql));
      db.execute("rollback to savepoint amendment_sql");
    }
    db.execute("set local search_path=atlas_test");
  }

  @Test
  void lateReplayAndAsOfContextRetainRecordedPolicy() {
    base("AMEND-LATE");
    var command = command(0, today().plusDays(2));
    var entry = amendments.amend(hr(), "AMEND-LATE", command, "initial");
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource());
    com.atlas.modules.corehr.application.port.out.UnitOfWork direct =
        new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> work) {
            return work.get();
          }
        };
    var at =
        new com.atlas.modules.corehr.application.usecase.CalendarAmendmentsService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarStore(jdbc),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarAmendmentStore(jdbc),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarRetirementStore(
                org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource())),
            direct,
            direct,
            Clock.fixed(
                today().plusDays(3).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    assertEquals(entry, at.amend(hr(), "AMEND-LATE", command, "late-retry"));
    assertEquals(1, at.context(hr(), "AMEND-LATE").currentVersion());
    assertEquals(entry.revision().policy(), at.context(hr(), "AMEND-LATE").currentPolicy());
  }
}
