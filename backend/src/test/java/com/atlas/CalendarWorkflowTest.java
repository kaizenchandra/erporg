package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.CalendarModels.*;
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
class CalendarWorkflowTest {
  @Autowired CalendarsUseCases calendars;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;

  Actor hr() {
    return people.actor("amelia");
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  Create command(String code, LocalDate start) {
    return new Create(
        new Input(
            code,
            "Synthetic company calendar 李",
            "Europe/London",
            start.toString(),
            start.plusDays(30).toString(),
            List.of(
                BigDecimal.ONE,
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(3),
                BigDecimal.valueOf(4),
                BigDecimal.valueOf(5)),
            List.of(start.plusDays(1).toString())),
        "Explicit synthetic company policy",
        UUID.randomUUID().toString());
  }

  @Test
  void persistsCompleteSnapshotWithProvenanceReplayAndAuthoritativePreview() {
    var c = command("CAL-ONE", today());
    var e = calendars.create(hr(), c, "calendar-correlation");
    assertEquals(e, calendars.create(hr(), c, "retry"));
    assertEquals(e, calendars.detail(hr(), "CAL-ONE"));
    assertEquals("calendar-correlation", e.correlationId());
    assertEquals(
        "COMPANY_CLOSURE",
        calendars.preview(hr(), "CAL-ONE", today().plusDays(1).toString()).classification());
    assertThrows(
        ChangeRequest.Validation.class,
        () -> calendars.preview(hr(), "CAL-ONE", today().minusDays(1).toString()));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            calendars.create(
                hr(), new Create(c.calendar(), "Changed policy", c.idempotencyKey()), "conflict"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> calendars.create(hr(), command("CAL-ONE", today()), "duplicate"));
  }

  @Test
  void filtersPagesAndLiteralSearchAreScopedAndOrdered() {
    for (int i = 0; i < 12; i++)
      calendars.create(
          hr(), command("CAL-PAGE-" + String.format("%02d", i), today().plusDays(1)), "page");
    var p = calendars.list(hr(), new Query("CAL-PAGE-", "PLANNED", 1, 5));
    assertEquals(12, p.total());
    assertEquals("CAL-PAGE-05", p.items().getFirst().calendar().code());
    assertEquals(2, calendars.list(hr(), new Query("CAL-PAGE-", "ALL", 2, 5)).items().size());
    assertEquals(0, calendars.list(hr(), new Query("%", "ALL", 0, 10)).total());
    assertEquals(0, calendars.list(hr(), new Query("CAL-PAGE-", "CURRENT", 0, 10)).total());
    assertEquals(0, calendars.list(hr(), new Query("CAL-PAGE-", "EXPIRED", 0, 10)).total());
  }

  @Test
  void deniesNonHrAndOtherTenantDetailAndMutations() {
    calendars.create(hr(), command("CAL-PRIVATE", today()), "scope");
    for (var user : List.of("sophia", "marcus", "outsider")) {
      var a = people.actor(user);
      assertThrows(
          ChangeRequest.Denied.class, () -> calendars.list(a, new Query("", "ALL", 0, 10)));
      assertThrows(ChangeRequest.Denied.class, () -> calendars.detail(a, "CAL-PRIVATE"));
      assertThrows(
          ChangeRequest.Denied.class,
          () -> calendars.preview(a, "CAL-PRIVATE", today().toString()));
      assertThrows(
          ChangeRequest.Denied.class,
          () -> calendars.create(a, command("CAL-DENY", today()), "deny"));
    }
    var other = new Actor("other-calendar-hr", "other", "OTHER", "Other HR", "HR");
    assertEquals(0, calendars.list(other, new Query("", "ALL", 0, 10)).total());
    assertThrows(ChangeRequest.Denied.class, () -> calendars.detail(other, "CAL-PRIVATE"));
  }

  @Test
  void rejectsInvalidPolicyAndSqlMutationWithoutChangingSnapshot() {
    var c = command("CAL-GUARD", today());
    var e = calendars.create(hr(), c, "guard");
    var i = c.calendar();
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            calendars.create(
                hr(),
                new Create(
                    new Input(
                        "CAL-BAD",
                        i.name(),
                        i.timeZone(),
                        i.effectiveFrom(),
                        i.effectiveUntil(),
                        List.of(new BigDecimal("1.5")),
                        i.closedDates()),
                    c.reason(),
                    UUID.randomUUID().toString()),
                "fraction"));
    for (var statement :
        List.of(
            "update hr_work_calendar set reason=reason",
            "delete from hr_work_calendar",
            "truncate hr_work_calendar cascade",
            "insert into hr_work_calendar select tenant,'CAL-BAD',name,time_zone,effective_from,effective_until,array[1,1]::smallint[],closed_dates,created_by,reason,recorded_at,correlation_id,'guard-negative-one' from hr_work_calendar where code='CAL-GUARD'",
            "insert into hr_work_calendar select tenant,'CAL-BAD',name,time_zone,effective_from,effective_until,working_days,array[effective_until+1],created_by,reason,recorded_at,correlation_id,'guard-negative-two' from hr_work_calendar where code='CAL-GUARD'")) {
      db.execute("savepoint calendar_guard");
      assertThrows(org.springframework.dao.DataAccessException.class, () -> db.execute(statement));
      db.execute("rollback to savepoint calendar_guard");
    }
    assertEquals(e, calendars.detail(hr(), "CAL-GUARD"));
  }

  @Test
  void lateReplayPreservesReceiptAndExpiredHistoryWhileNewBackdatingFails() {
    var c = command("CAL-HISTORY", today());
    var saved = calendars.create(hr(), c, "history");
    var store =
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarStore(
            org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource()));
    com.atlas.modules.corehr.application.port.out.UnitOfWork direct =
        new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> action) {
            return action.get();
          }
        };
    var future =
        Clock.fixed(today().plusDays(40).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    var later =
        new com.atlas.modules.corehr.application.usecase.CalendarsService(
            store,
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarAmendmentStore(
                org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource())),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarRetirementStore(
                org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource())),
            direct,
            direct,
            future);
    assertEquals(saved, later.create(hr(), c, "late-retry"));
    assertEquals(1, later.list(hr(), new Query("CAL-HISTORY", "EXPIRED", 0, 10)).total());
    assertEquals(saved, later.detail(hr(), "CAL-HISTORY"));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> later.create(hr(), command("CAL-PAST", today()), "past"));
  }

  @Test
  void catalogueCapRejectsWithoutPartialCreation() {
    var c = command("CAL-SEED", today());
    calendars.create(hr(), c, "seed");
    db.execute(
        "insert into hr_work_calendar select tenant,'CAP-'||lpad(n::text,3,'0'),name,time_zone,effective_from,effective_until,working_days,closed_dates,created_by,reason,recorded_at,correlation_id,'calendar-cap-'||n from hr_work_calendar cross join generate_series(1,199) n where code='CAL-SEED'");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> calendars.create(hr(), command("CAL-OVER", today()), "over"));
    assertEquals(200, calendars.list(hr(), new Query("", "ALL", 0, 50)).total());
    assertEquals(0, calendars.list(hr(), new Query("CAL-OVER", "ALL", 0, 10)).total());
  }
}
