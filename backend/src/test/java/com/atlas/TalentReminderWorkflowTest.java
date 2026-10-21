package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.api.TalentWorkforceAccess;
import com.atlas.modules.payroll.api.*;
import com.atlas.modules.talent.adapter.out.integration.CoreHrTalentEnvironment;
import com.atlas.modules.talent.adapter.out.persistence.JdbcTalentStore;
import com.atlas.modules.talent.application.model.ReminderModels;
import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.application.port.in.*;
import com.atlas.modules.talent.application.port.out.TalentPorts.Transactions;
import com.atlas.modules.talent.application.usecase.ReminderWorkflows;
import com.atlas.shared.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TalentReminderWorkflowTest {
  static final String SCHEMA =
      "atlas_talent_reminders_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://localhost:5438/atlas?currentSchema=" + SCHEMA);
    r.add("spring.flyway.default-schema", () -> SCHEMA);
    r.add("spring.flyway.schemas", () -> SCHEMA);
  }

  @Autowired ReminderUseCases jobs;
  @Autowired TalentUseCases talent;
  @Autowired JdbcTemplate db;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired TalentWorkforceAccess workforce;
  @Autowired TalentIntakeAccess intake;
  @Autowired CompensationAccess compensation;

  String key() {
    return UUID.randomUUID().toString();
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  void pending() {
    var r =
        talent.requisition(
            "amelia",
            new RequisitionInput(
                "Reminder synthetic vacancy",
                "Design",
                "London, UK",
                "E002",
                today(),
                "Test pending approval",
                key()),
            "create");
    talent.requisitionAction(
        "amelia",
        r.meta().id(),
        new Decision(BigDecimal.ZERO, "SUBMIT", "Pending independent review", key()),
        "submit");
  }

  ReminderUseCases tickAfter(int hours) {
    var t = new TransactionTemplate(manager);
    t.setTimeout(10);
    var boundary =
        new Transactions() {
          public <T> T run(Supplier<T> s) {
            return t.execute(status -> s.get());
          }
        };
    return new ReminderWorkflows(
        new JdbcTalentStore(jdbc),
        new CoreHrTalentEnvironment(workforce, intake, compensation),
        boundary,
        boundary,
        Clock.offset(Clock.systemUTC(), Duration.ofHours(hours)),
        true);
  }

  @Test
  void committedReminderBatchDeduplicatesAndResumesAfterBoundedDurableFailure() {
    assertThrows(AccessDenied.class, () -> jobs.context("sophia", 0));
    pending();
    var configure =
        new ReminderModels.Configure(
            BigDecimal.ZERO,
            true,
            0,
            60,
            "Explicit synthetic immediate reminders; not a default business deadline",
            key());
    var policy = jobs.configure("amelia", configure, "policy");
    assertEquals(policy, jobs.configure("amelia", configure, "replay"));
    assertFalse(jobs.context("olivia", 0).schedulerEnabled());
    var command = new ReminderModels.Command("Deliver configured in-app reminders", key());
    var first = jobs.run("olivia", command, "manual");
    assertEquals("COMMITTED", first.status());
    assertEquals(1, first.processed());
    assertEquals(1, first.delivered());
    assertEquals(first, jobs.run("olivia", command, "replay"));
    assertEquals("DELIVERED", jobs.items("olivia", first.id()).getFirst().status());
    assertTrue(
        talent.notices("olivia", 0).stream().anyMatch(n -> n.status().equals("APPROVAL_REMINDER")));
    assertEquals(
        0,
        jobs.run(
                "olivia",
                new ReminderModels.Command("Recheck without duplicate delivery", key()),
                "duplicate")
            .processed());
    assertThrows(AccessDenied.class, () -> jobs.items("sophia", first.id()));
    var delayed =
        jobs.configure(
            "amelia",
            new ReminderModels.Configure(
                BigDecimal.ONE, true, 525600, 60, "Explicit configured age threshold", key()),
            "age");
    pending();
    assertEquals(
        0,
        jobs.run(
                "olivia",
                new ReminderModels.Command("Not yet due under configured age", key()),
                "not-due")
            .processed());
    jobs.configure(
        "amelia",
        new ReminderModels.Configure(
            BigDecimal.valueOf(delayed.version()),
            true,
            0,
            60,
            "Explicit immediate synthetic recovery exercise",
            key()),
        "reset");
    db.execute(
        "create function reminder_delivery_fault() returns trigger language plpgsql as $$begin raise exception 'SYNTHETIC_DELIVERY_FAILURE';end $$");
    db.execute(
        "create trigger reminder_delivery_fault before insert on tal_reminder_notice for each row execute function reminder_delivery_fault()");
    try {
      for (int i = 1; i <= 3; i++) tickAfter(i).tick();
    } finally {
      db.execute("drop trigger reminder_delivery_fault on tal_reminder_notice");
      db.execute("drop function reminder_delivery_fault()");
    }
    var failed = jobs.context("olivia", 0);
    assertEquals("FAILED", failed.state().status());
    assertEquals(3, failed.state().failures());
    assertEquals(3, failed.runs().stream().filter(r -> r.status().equals("FAILED")).count());
    assertEquals(1, db.queryForObject("select count(*) from tal_reminder_notice", Integer.class));
    var retry =
        new ReminderModels.Command("Inspected local delivery fault; replay after repair", key());
    var ready = jobs.retry("olivia", retry, "retry");
    assertEquals("READY", ready.status());
    assertEquals(ready, jobs.retry("olivia", retry, "replay"));
    var repaired =
        jobs.run(
            "olivia",
            new ReminderModels.Command("Reconcile repaired in-app processing", key()),
            "recovered");
    assertEquals(1, repaired.processed());
    assertEquals(1, repaired.delivered());
    assertEquals(2, db.queryForObject("select count(*) from tal_reminder_notice", Integer.class));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("update tal_reminder_run set snapshot=snapshot"));
  }
}
