package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.ExitTaskModels.*;
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
class ExitTaskWorkflowTest {
  @Autowired ExitTaskUseCases tasks;
  @Autowired ResignationUseCases resignations;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;

  String request(boolean approved) {
    var e = people.profile(people.actor("sophia"), "E003");
    var r =
        resignations.submit(
            "sophia",
            new ResignationCommands.Submit(
                LocalDate.now(ZoneOffset.UTC).plusDays(30),
                "Private resignation reason",
                BigDecimal.valueOf(e.version()),
                UUID.randomUUID().toString()),
            "request");
    if (approved)
      resignations.review(
          "amelia",
          r.id(),
          new ResignationCommands.Review(
              "APPROVED", "Private HR decision", BigDecimal.ZERO, UUID.randomUUID().toString()),
          "approved");
    return r.id();
  }

  Create command(long version) {
    return new Create(
        "E002",
        "Collect synthetic equipment",
        "Record the handover reference only",
        LocalDate.now(ZoneOffset.UTC).plusDays(10),
        "Coordinate approved exit",
        BigDecimal.valueOf(version),
        UUID.randomUUID().toString());
  }

  Complete complete() {
    return new Complete(
        "Synthetic handover recorded", BigDecimal.ZERO, UUID.randomUUID().toString());
  }

  @Test
  void assignedTaskCompletesWithAuditNotificationsAndNoEmploymentMutation() {
    var id = request(true);
    var c = command(0);
    var t = tasks.create("amelia", id, c, "task-created");
    assertEquals(t.task().id(), tasks.create("amelia", id, c, "replay").task().id());
    assertEquals(1, tasks.mine("marcus", new Query("OPEN", 0, 1)).total());
    assertEquals(0, tasks.mine("sophia", new Query("ALL", 0, 1)).total());
    assertThrows(
        ChangeRequest.Denied.class,
        () -> tasks.complete("amelia", t.task().id(), complete(), "proxy"));
    var done = complete();
    var result = tasks.complete("marcus", t.task().id(), done, "task-completed");
    assertEquals("COMPLETED", result.task().status());
    assertFalse(result.overdue());
    assertEquals(result, tasks.complete("marcus", t.task().id(), done, "replay"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> tasks.complete("marcus", t.task().id(), complete(), "duplicate"));
    assertEquals(2, tasks.history("marcus", t.task().id()).size());
    assertEquals(
        "task-completed", tasks.history("amelia", t.task().id()).getLast().correlationId());
    assertEquals("Active", people.profile(people.actor("sophia"), "E003").status());
    assertEquals(
        "PENDING_EXIT_COORDINATION",
        db.queryForObject(
            "select status from hr_resignation_handoff where request_id=?", String.class, id));
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from hr_notification where subject='marcus' and message like 'An exit task is assigned%'",
            Integer.class));
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from hr_notification where subject='amelia' and message like 'An assigned exit task was completed%'",
            Integer.class));
    db.execute("set constraints all immediate");
  }

  @Test
  void taskAssignmentDoesNotGrantPrivateResignationOrOtherTaskAccess() {
    var id = request(true);
    var t = tasks.create("amelia", id, command(0), "created");
    assertThrows(ChangeRequest.Denied.class, () -> resignations.history("marcus", id));
    for (var user : List.of("sophia", "outsider")) {
      assertThrows(ChangeRequest.Denied.class, () -> tasks.history(user, t.task().id()));
      assertThrows(
          ChangeRequest.Denied.class,
          () -> tasks.complete(user, t.task().id(), complete(), "forbidden"));
      assertEquals(0, tasks.mine(user, new Query("ALL", 0, 1)).total());
    }
    assertThrows(ChangeRequest.Denied.class, () -> tasks.forCase("marcus", id));
    assertThrows(ChangeRequest.Denied.class, () -> tasks.forCase("outsider", id));
    assertThrows(
        ChangeRequest.Denied.class, () -> tasks.create("marcus", id, command(1), "manager"));
    assertThrows(
        ChangeRequest.Denied.class, () -> tasks.create("outsider", id, command(1), "tenant"));
  }

  @Test
  void approvalAndIndependentCoordinatorAreRequired() {
    var id = request(false);
    assertThrows(
        ChangeRequest.Conflict.class, () -> tasks.create("amelia", id, command(0), "pending"));
    resignations.review(
        "amelia",
        id,
        new ResignationCommands.Review(
            "REJECTED", "Rejected", BigDecimal.ZERO, UUID.randomUUID().toString()),
        "rejected");
    assertThrows(
        ChangeRequest.Conflict.class, () -> tasks.create("amelia", id, command(0), "rejected"));
    var me = people.profile(people.actor("amelia"), "E001");
    var own =
        resignations.submit(
            "amelia",
            new ResignationCommands.Submit(
                LocalDate.now(ZoneOffset.UTC).plusDays(30),
                "Own resignation",
                BigDecimal.valueOf(me.version()),
                UUID.randomUUID().toString()),
            "own");
    resignations.review(
        "olivia",
        own.id(),
        new ResignationCommands.Review(
            "APPROVED", "Independent", BigDecimal.ZERO, UUID.randomUUID().toString()),
        "approved");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> tasks.create("amelia", own.id(), command(0), "self-coordinator"));
  }

  @Test
  void exactVersionsReplayValidationAndUnknownAssigneeFailSafely() {
    var id = request(true);
    var c = command(0);
    for (var v : Arrays.asList(null, new BigDecimal("0.1"), new BigDecimal("-1"))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              tasks.create(
                  "amelia",
                  id,
                  new Create(
                      c.assigneeId(),
                      c.title(),
                      c.instructions(),
                      c.dueDate(),
                      c.reason(),
                      v,
                      c.idempotencyKey()),
                  "invalid"));
    }
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            tasks.create(
                "amelia",
                id,
                new Create(
                    "E900",
                    c.title(),
                    c.instructions(),
                    c.dueDate(),
                    c.reason(),
                    c.version(),
                    c.idempotencyKey()),
                "other-tenant"));
    var t = tasks.create("amelia", id, c, "created");
    assertThrows(
        ChangeRequest.Conflict.class, () -> tasks.create("amelia", id, command(0), "stale-list"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            tasks.create(
                "amelia",
                id,
                new Create(
                    "E003",
                    c.title(),
                    c.instructions(),
                    c.dueDate(),
                    c.reason(),
                    c.version(),
                    c.idempotencyKey()),
                "changed-replay"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tasks.complete(
                "marcus",
                t.task().id(),
                new Complete("Done", null, UUID.randomUUID().toString()),
                "missing"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            tasks.complete(
                "marcus",
                t.task().id(),
                new Complete(" ", BigDecimal.ZERO, UUID.randomUUID().toString()),
                "blank"));
  }

  @Test
  void overdueUsesInjectedClockAndDoesNotPreventCompletionOrReplay() {
    var id = request(true);
    var c = command(0);
    var t = tasks.create("amelia", id, c, "created");
    var client = org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource());
    com.atlas.modules.corehr.application.port.out.UnitOfWork tx =
        new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> work) {
            return work.get();
          }
        };
    var clock =
        Clock.fixed(
            c.dueDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    var later =
        new com.atlas.modules.corehr.application.usecase.ExitTaskService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcExitTaskStore(client),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcResignationStore(client),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(client),
            tx,
            tx,
            clock);
    assertTrue(later.mine("marcus", new Query("OPEN", 0, 12)).items().getFirst().overdue());
    assertEquals(t.task().id(), later.create("amelia", id, c, "delayed-replay").task().id());
    assertFalse(later.complete("marcus", t.task().id(), complete(), "late-attestation").overdue());
    db.execute("set constraints all immediate");
  }

  @Test
  void databaseRejectsSnapshotMutationAndUnmatchedTerminalAudit() {
    var t = tasks.create("amelia", request(true), command(0), "created").task();
    db.execute("savepoint before_tamper");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("update hr_exit_task set assignee_id='E003' where id=?", t.id()));
    db.execute("rollback to savepoint before_tamper");
    db.update(
        "insert into hr_exit_task_audit(tenant,task_id,old_status,new_status,actor,reason,effective_date,recorded_at,correlation_id) values('atlas',?,'OPEN','COMPLETED','marcus','Unmatched',?,now(),'invalid')",
        t.id(),
        t.dueDate());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.execute("set constraints all immediate"));
    db.execute("rollback to savepoint before_tamper");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("delete from hr_exit_task_audit where task_id=?", t.id()));
    db.execute("rollback to savepoint before_tamper");
  }

  @Test
  void listPaginationAndFiftyTaskGuardAreExplicit() {
    var id = request(true);
    for (int n = 0; n < 50; n++) tasks.create("amelia", id, command(n), "bounded");
    var page = tasks.mine("marcus", new Query("ALL", 4, 12));
    assertEquals(50, page.total());
    assertEquals(2, page.items().size());
    assertEquals(50, tasks.forCase("amelia", id).version());
    assertThrows(
        ChangeRequest.Conflict.class, () -> tasks.create("amelia", id, command(50), "overflow"));
    db.execute("set constraints all immediate");
  }

  @Test
  void unconfiguredAssigneesAndReboundIdentitiesCannotCompleteTasks() {
    var id = request(true);
    var c = command(0);
    var missing =
        db.queryForObject(
            "select id from hr_employee e where tenant='atlas' and not exists(select 1 from identity_membership m where m.tenant=e.tenant and m.employee_id=e.id) order by id limit 1",
            String.class);
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            tasks.create(
                "amelia",
                id,
                new Create(
                    missing,
                    c.title(),
                    c.instructions(),
                    c.dueDate(),
                    c.reason(),
                    c.version(),
                    c.idempotencyKey()),
                "no-identity"));
    var t = tasks.create("amelia", id, c, "created");
    db.update("update identity_membership set employee_id='E003' where subject='marcus'");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> tasks.complete("marcus", t.task().id(), complete(), "rebound"));
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) values('assigned-alias','atlas','E002','Assigned alias','EMPLOYEE')");
    assertEquals(
        "COMPLETED",
        tasks
            .complete("assigned-alias", t.task().id(), complete(), "assigned-alias")
            .task()
            .status());
    db.execute("set constraints all immediate");
  }
}
