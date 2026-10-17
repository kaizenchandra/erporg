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
class ExitTaskManagementWorkflowTest {
  @Autowired ExitTaskUseCases tasks;
  @Autowired ResignationUseCases resignations;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;

  String request() {
    var e = people.profile(people.actor("sophia"), "E003");
    var r =
        resignations.submit(
            "sophia",
            new ResignationCommands.Submit(
                LocalDate.now(ZoneOffset.UTC).plusDays(30),
                "Private resignation",
                BigDecimal.valueOf(e.version()),
                UUID.randomUUID().toString()),
            "submitted");
    resignations.review(
        "amelia",
        r.id(),
        new ResignationCommands.Review(
            "APPROVED", "Private review", BigDecimal.ZERO, UUID.randomUUID().toString()),
        "approved");
    return r.id();
  }

  Create create() {
    return new Create(
        "E002",
        "Synthetic task",
        "Operational instructions only",
        LocalDate.now(ZoneOffset.UTC).plusDays(5),
        "Coordinate exit",
        BigDecimal.ZERO,
        UUID.randomUUID().toString());
  }

  Manage command(String action, String assignee, long version) {
    return new Manage(
        action,
        assignee,
        "Private management reason",
        BigDecimal.valueOf(version),
        UUID.randomUUID().toString());
  }

  Complete complete(long v) {
    return new Complete("Manual attestation", BigDecimal.valueOf(v), UUID.randomUUID().toString());
  }

  @Test
  void reassignmentRevokesFormerAssigneeAndPreservesOriginalCreationReplay() {
    var id = request();
    var c = create();
    var t = tasks.create("amelia", id, c, "created").task();
    var m = command("REASSIGN", "E003", 0);
    var event = tasks.manage("amelia", t.id(), m, "reassigned");
    assertEquals("E002", event.oldAssigneeId());
    assertEquals("E003", event.newAssigneeId());
    assertEquals(event, tasks.manage("amelia", t.id(), m, "replay"));
    assertEquals(0, tasks.mine("marcus", new Query("ALL", 0, 12)).total());
    assertEquals(
        "E003",
        tasks.mine("sophia", new Query("OPEN", 0, 12)).items().getFirst().task().assigneeId());
    assertThrows(ChangeRequest.Denied.class, () -> tasks.history("marcus", t.id()));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> tasks.complete("marcus", t.id(), complete(0), "old-owner"));
    assertThrows(ChangeRequest.Denied.class, () -> tasks.managementHistory("sophia", t.id()));
    var replay = tasks.create("amelia", id, c, "creation-replay");
    assertEquals("E003", replay.task().assigneeId());
    assertEquals(1, replay.task().version());
    assertEquals(
        "E002",
        db.queryForObject("select assignee_id from hr_exit_task where id=?", String.class, t.id()));
    var done = complete(1);
    assertEquals(2, tasks.complete("sophia", t.id(), done, "completed").task().version());
    assertEquals(
        "COMPLETED", tasks.complete("sophia", t.id(), done, "completion-replay").task().status());
    assertEquals(1, tasks.managementHistory("amelia", t.id()).size());
    assertEquals(2, tasks.history("sophia", t.id()).size());
    assertEquals(0, tasks.mine("marcus", new Query("COMPLETED", 0, 12)).total());
    db.execute("set constraints all immediate");
  }

  @Test
  void cancellationIsTerminalVisibleAndDoesNotMutateExitOrEmployment() {
    var id = request();
    var t = tasks.create("amelia", id, create(), "created").task();
    var c = command("CANCEL", null, 0);
    var e = tasks.manage("olivia", t.id(), c, "cancelled");
    assertEquals("CANCELLED", e.newStatus());
    assertEquals(e, tasks.manage("olivia", t.id(), c, "replay"));
    var cancelled = tasks.mine("marcus", new Query("CANCELLED", 0, 12));
    assertEquals(1, cancelled.total());
    assertFalse(cancelled.items().getFirst().overdue());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> tasks.complete("marcus", t.id(), complete(1), "cancelled"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> tasks.manage("amelia", t.id(), command("REASSIGN", "E003", 1), "terminal"));
    assertEquals(
        "OPEN",
        db.queryForObject("select status from hr_exit_task where id=?", String.class, t.id()));
    assertEquals(1, tasks.history("marcus", t.id()).size());
    assertEquals(
        "PENDING_EXIT_COORDINATION",
        db.queryForObject(
            "select status from hr_resignation_handoff where request_id=?", String.class, id));
    assertEquals("Active", people.profile(people.actor("sophia"), "E003").status());
    db.execute("set constraints all immediate");
  }

  @Test
  void validationScopeStalenessAndConflictingReplayCannotChangeManagement() {
    var t = tasks.create("amelia", request(), create(), "created").task();
    for (var user : List.of("marcus", "sophia", "outsider")) {
      assertThrows(
          ChangeRequest.Denied.class,
          () -> tasks.manage(user, t.id(), command("CANCEL", null, 0), "denied"));
      assertThrows(ChangeRequest.Denied.class, () -> tasks.managementHistory(user, t.id()));
    }
    for (var v : Arrays.asList(null, new BigDecimal("0.5"), new BigDecimal("-1")))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              tasks.manage(
                  "amelia",
                  t.id(),
                  new Manage("CANCEL", null, "Reason", v, UUID.randomUUID().toString()),
                  "invalid"));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> tasks.manage("amelia", t.id(), command("REASSIGN", "E002", 0), "same"));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> tasks.manage("amelia", t.id(), command("REASSIGN", "E900", 0), "tenant"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            tasks.manage(
                "amelia",
                t.id(),
                new Manage(
                    "CANCEL", "E003", "Reason", BigDecimal.ZERO, UUID.randomUUID().toString()),
                "cancel-target"));
    var c = command("REASSIGN", "E003", 0);
    tasks.manage("amelia", t.id(), c, "reassigned");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> tasks.manage("amelia", t.id(), command("CANCEL", null, 0), "stale"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            tasks.manage(
                "amelia",
                t.id(),
                new Manage("REASSIGN", "E001", c.reason(), c.version(), c.idempotencyKey()),
                "conflicting"));
  }

  @Test
  void formerHrRoleAndDepartingHrIdentityCannotCoordinate() {
    var t = tasks.create("amelia", request(), create(), "created").task();
    db.update("update identity_membership set role='HR' where subject='sophia'");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> tasks.manage("sophia", t.id(), command("CANCEL", null, 0), "own-exit"));
    db.update("update identity_membership set role='EMPLOYEE' where subject='amelia'");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> tasks.manage("amelia", t.id(), command("CANCEL", null, 0), "downgraded"));
  }

  @Test
  void managementJournalIsImmutableAndDatabaseRejectsOldAssigneeCompletion() {
    var t = tasks.create("amelia", request(), create(), "created").task();
    tasks.manage("amelia", t.id(), command("REASSIGN", "E003", 0), "reassigned");
    db.execute("savepoint management_guard");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("delete from hr_exit_task_management where task_id=?", t.id()));
    db.execute("rollback to savepoint management_guard");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "update hr_exit_task set status='COMPLETED',version=1,completed_by='marcus',completion_comment='Old owner',completed_at=now(),completion_key='old-owner-key' where id=?",
                t.id()));
    db.execute("rollback to savepoint management_guard");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "insert into hr_exit_task_management(tenant,task_id,ordinal,action,old_assignee_id,new_assignee_id,old_status,new_status,actor,actor_employee_id,reason,input_version,idempotency_key,recorded_at,correlation_id) values('atlas',?,3,'CANCEL','E003','E003','OPEN','CANCELLED','amelia','E001','Gap',2,'gap-command-key',now(),'gap')",
                t.id()));
    db.execute("rollback to savepoint management_guard");
  }

  @Test
  void boundedManagementStillAllowsCurrentAssigneeCompletion() {
    var id = request();
    var t = tasks.create("amelia", id, create(), "created").task();
    for (int n = 0; n < 100; n++)
      tasks.manage(
          "amelia", t.id(), command("REASSIGN", n % 2 == 0 ? "E003" : "E002", n), "bounded");
    assertEquals(100, tasks.managementHistory("amelia", t.id()).size());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> tasks.manage("amelia", t.id(), command("CANCEL", null, 100), "cap"));
    assertEquals(
        101, tasks.complete("marcus", t.id(), complete(100), "completed").task().version());
    db.execute("set constraints all immediate");
  }

  @Test
  void managementReplayRemainsStableAfterCompletionAndDoesNotRenotify() {
    var t = tasks.create("amelia", request(), create(), "created").task();
    var c = command("REASSIGN", "E003", 0);
    var event = tasks.manage("amelia", t.id(), c, "reassigned");
    tasks.complete("sophia", t.id(), complete(1), "done");
    var notifications = db.queryForObject("select count(*) from hr_notification", Long.class);
    assertEquals(event, tasks.manage("amelia", t.id(), c, "late-replay"));
    assertEquals(
        notifications, db.queryForObject("select count(*) from hr_notification", Long.class));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> tasks.manage("amelia", t.id(), command("CANCEL", null, 2), "completed"));
  }

  @Test
  void managementRequiresConfiguredAssigneeAndCannotCompleteCancelledTaskThroughSql() {
    var t = tasks.create("amelia", request(), create(), "created").task();
    var missing =
        db.queryForObject(
            "select id from hr_employee e where tenant='atlas' and not exists(select 1 from identity_membership m where m.tenant=e.tenant and m.employee_id=e.id) order by id limit 1",
            String.class);
    assertThrows(
        ChangeRequest.Validation.class,
        () -> tasks.manage("amelia", t.id(), command("REASSIGN", missing, 0), "unconfigured"));
    tasks.manage("amelia", t.id(), command("CANCEL", null, 0), "cancelled");
    db.execute("savepoint cancel_guard");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "update hr_exit_task set status='COMPLETED',version=1,completed_by='marcus',completion_comment='Cancelled',completed_at=now(),completion_key='cancelled-key' where id=?",
                t.id()));
    db.execute("rollback to savepoint cancel_guard");
  }
}
