package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.adapter.out.persistence.*;
import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.EmploymentEndModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
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
class EndRescheduleWorkflowTest {
  @Autowired EmploymentEndUseCases ends;
  @Autowired EndRescheduleUseCases reschedules;
  @Autowired ResignationUseCases resignations;
  @Autowired PeopleUseCases people;
  @Autowired ExitTaskUseCases tasks;
  @Autowired JdbcTemplate db;

  String key() {
    return UUID.randomUUID().toString();
  }

  EmploymentEnd schedule() {
    var e = people.profile(people.actor("sophia"), "E003");
    var r =
        resignations.submit(
            "sophia",
            new ResignationCommands.Submit(
                LocalDate.now(ZoneOffset.UTC).plusDays(30),
                "Synthetic resignation",
                BigDecimal.valueOf(e.version()),
                key()),
            "submitted");
    resignations.review(
        "amelia",
        r.id(),
        new ResignationCommands.Review("APPROVED", "Independent", BigDecimal.ZERO, key()),
        "approved");
    return ends.confirm(
        "amelia",
        r.id(),
        new Confirm("Agreed end", BigDecimal.valueOf(e.version()), key()),
        "scheduled");
  }

  EndRescheduleModels.Submit command(EmploymentEnd end, LocalDate last) {
    return new EndRescheduleModels.Submit(
        last,
        "Correct agreed end date",
        BigDecimal.valueOf(reschedules.context("amelia", end.requestId()).employeeVersion()),
        key());
  }

  EndRescheduleModels.Review review(EmploymentEnd end, String decision) {
    return new EndRescheduleModels.Review(
        decision,
        "Independent date review",
        BigDecimal.valueOf(reschedules.context("amelia", end.requestId()).employeeVersion()),
        key());
  }

  void withdraw(EmploymentEnd end) {
    var w =
        ends.withdraw(
            "amelia",
            end.requestId(),
            new Confirm("Continue employment", BigDecimal.valueOf(end.newVersion()), key()),
            "withdrawn-request");
    ends.reviewWithdrawal(
        "olivia",
        end.requestId(),
        w.id(),
        new ReviewWithdrawal(
            "APPROVED", "Independent", BigDecimal.valueOf(end.newVersion()), key()),
        "withdrawal-approved");
  }

  EndRescheduleUseCases at(Instant instant) {
    var client = JdbcClient.create(db.getDataSource());
    var tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> work) {
            return work.get();
          }
        };
    return new com.atlas.modules.corehr.application.usecase.EndRescheduleService(
        new JdbcEndRescheduleStore(client),
        new JdbcEmploymentEndStore(client),
        new JdbcPeopleStore(client),
        tx,
        tx,
        Clock.fixed(instant, ZoneOffset.UTC));
  }

  @Test
  void pendingRejectedAndApprovedDatesPreserveOriginalAndMoveCurrentCutoff() {
    var e = schedule();
    var c = command(e, e.lastWorkingDate().plusDays(5));
    var r = reschedules.submit("amelia", e.requestId(), c, "submitted");
    assertEquals(r, reschedules.submit("amelia", e.requestId(), c, "replay"));
    assertEquals(e, ends.context("amelia", e.requestId()).currentRecord());
    reschedules.review("olivia", e.requestId(), r.id(), review(e, "REJECTED"), "rejected");
    assertEquals(e, ends.context("amelia", e.requestId()).currentRecord());
    var next =
        reschedules.submit(
            "amelia", e.requestId(), command(e, e.lastWorkingDate().plusDays(10)), "fresh");
    var decision = review(e, "APPROVED");
    var approved = reschedules.review("olivia", e.requestId(), next.id(), decision, "approved");
    assertEquals(
        approved, reschedules.review("olivia", e.requestId(), next.id(), decision, "replay"));
    var context = ends.context("amelia", e.requestId());
    assertTrue(context.replaced());
    assertEquals(e, context.record());
    assertEquals(next.lastWorkingDate().plusDays(1), context.inactiveDate());
    assertEquals(e.newVersion() + 1, context.employeeVersion());
    var store = new JdbcPeopleStore(JdbcClient.create(db.getDataSource()));
    assertEquals(
        "Active", store.employee(people.actor("amelia"), "E003", e.inactiveDate(), false).status());
    assertEquals(
        "Ended",
        store.employee(people.actor("amelia"), "E003", context.inactiveDate(), false).status());
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from hr_employment_end_handoff_current where employee_id='E003' and status='PENDING'",
            Integer.class));
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from hr_employment_end_handoff_current where employee_id='E003' and status='SUPPRESSED'",
            Integer.class));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            reschedules.submit(
                "amelia",
                e.requestId(),
                command(e, e.lastWorkingDate().plusDays(15)),
                "second-approved"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            ends.withdraw(
                "amelia",
                e.requestId(),
                new Confirm(
                    "Withdraw replacement", BigDecimal.valueOf(context.employeeVersion()), key()),
                "unsupported"));
    db.execute("set constraints all immediate");
  }

  @Test
  void withdrawnScheduleMayBeRescheduledEvenAfterItsSuppressedOriginalCutoff() {
    var e = schedule();
    withdraw(e);
    var now = e.inactiveDate().plusDays(5).atStartOfDay(ZoneOffset.UTC).toInstant();
    var service = at(now);
    var c = command(e, e.lastWorkingDate().plusDays(15));
    var r = service.submit("amelia", e.requestId(), c, "reschedule");
    assertNull(r.priorInactiveDate());
    service.review("olivia", e.requestId(), r.id(), review(e, "APPROVED"), "approved");
    assertTrue(ends.context("amelia", e.requestId()).replaced());
    db.execute("set constraints all immediate");
  }

  @Test
  void earlierDateHasAnEarlierCurrentCutoff() {
    var e = schedule();
    var r =
        reschedules.submit(
            "amelia", e.requestId(), command(e, e.lastWorkingDate().minusDays(10)), "earlier");
    reschedules.review("olivia", e.requestId(), r.id(), review(e, "APPROVED"), "approved");
    var store = new JdbcPeopleStore(JdbcClient.create(db.getDataSource()));
    assertEquals(
        "Ended",
        store
            .employee(people.actor("amelia"), "E003", r.lastWorkingDate().plusDays(1), false)
            .status());
    assertThrows(
        ChangeRequest.Denied.class, () -> store.actor("sophia", r.lastWorkingDate().plusDays(1)));
  }

  @Test
  void roleTenantTargetAndIdentityAliasCannotEscalate() {
    var e = schedule();
    var c = command(e, e.lastWorkingDate().plusDays(5));
    for (var user : List.of("sophia", "marcus", "outsider")) {
      assertThrows(ChangeRequest.Denied.class, () -> reschedules.context(user, e.requestId()));
      assertThrows(
          ChangeRequest.Denied.class, () -> reschedules.submit(user, e.requestId(), c, "denied"));
    }
    var r = reschedules.submit("amelia", e.requestId(), c, "submitted");
    var review = review(e, "APPROVED");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> reschedules.review("amelia", e.requestId(), r.id(), review, "self"));
    db.update("update identity_membership set employee_id='E001',role='HR' where subject='marcus'");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> reschedules.review("marcus", e.requestId(), r.id(), review, "alias"));
    db.update("update identity_membership set employee_id='E003',role='HR' where subject='olivia'");
    assertThrows(
        ChangeRequest.Denied.class, () -> reschedules.submit("olivia", e.requestId(), c, "target"));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> reschedules.review("olivia", e.requestId(), r.id(), review, "target"));
  }

  @Test
  void invalidDatesExactVersionsReplayAndStaleSnapshotFailSafely() {
    var e = schedule();
    var c = command(e, e.lastWorkingDate().plusDays(5));
    for (var v : Arrays.asList(null, new BigDecimal("0.5"), new BigDecimal("-1")))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              reschedules.submit(
                  "amelia",
                  e.requestId(),
                  new EndRescheduleModels.Submit(c.lastWorkingDate(), c.reason(), v, key()),
                  "invalid"));
    for (var date :
        Arrays.asList(
            null,
            LocalDate.now(ZoneOffset.UTC).minusDays(1),
            LocalDate.now(ZoneOffset.UTC).plusYears(2),
            e.lastWorkingDate()))
      assertThrows(
          ChangeRequest.Validation.class,
          () -> reschedules.submit("amelia", e.requestId(), command(e, date), "invalid-date"));
    var r = reschedules.submit("amelia", e.requestId(), c, "submitted");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            reschedules.submit(
                "amelia",
                e.requestId(),
                new EndRescheduleModels.Submit(
                    c.lastWorkingDate().plusDays(1), c.reason(), c.version(), c.idempotencyKey()),
                "key-conflict"));
    db.update("update hr_employee set version=version+1 where tenant='atlas' and id='E003'");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> reschedules.review("olivia", e.requestId(), r.id(), review(e, "APPROVED"), "stale"));
    reschedules.review("olivia", e.requestId(), r.id(), review(e, "REJECTED"), "reject-stale");
  }

  @Test
  void currentCutoffCannotBeMovedAfterItStartsButRequestMayBeRejected() {
    var e = schedule();
    var r =
        reschedules.submit(
            "amelia", e.requestId(), command(e, e.lastWorkingDate().plusDays(5)), "submitted");
    var service = at(e.inactiveDate().atStartOfDay(ZoneOffset.UTC).toInstant());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> service.review("olivia", e.requestId(), r.id(), review(e, "APPROVED"), "too-late"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            service.submit(
                "olivia", e.requestId(), command(e, e.lastWorkingDate().plusDays(10)), "too-late"));
    service.review("olivia", e.requestId(), r.id(), review(e, "REJECTED"), "closed");
  }

  @Test
  void pendingWithdrawalAndReplacementReviewsMutuallyExclude() {
    var e = schedule();
    var w =
        ends.withdraw(
            "amelia",
            e.requestId(),
            new Confirm("Withdraw", BigDecimal.valueOf(e.newVersion()), key()),
            "pending");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            reschedules.submit(
                "amelia", e.requestId(), command(e, e.lastWorkingDate().plusDays(5)), "blocked"));
    ends.reviewWithdrawal(
        "olivia",
        e.requestId(),
        w.id(),
        new ReviewWithdrawal(
            "REJECTED", "Retain original", BigDecimal.valueOf(e.newVersion()), key()),
        "rejected");
    reschedules.submit(
        "amelia", e.requestId(), command(e, e.lastWorkingDate().plusDays(5)), "pending");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            ends.withdraw(
                "amelia",
                e.requestId(),
                new Confirm("Withdraw", BigDecimal.valueOf(e.newVersion()), key()),
                "blocked"));
  }

  @Test
  void openTasksAndFutureReportsAreRecheckedAtApproval() {
    var e = schedule();
    withdraw(e);
    var r =
        reschedules.submit(
            "amelia", e.requestId(), command(e, e.lastWorkingDate().plusDays(5)), "submitted");
    var task =
        tasks
            .create(
                "amelia",
                e.requestId(),
                new ExitTaskModels.Create(
                    "E002",
                    "Follow-up",
                    "Record handover",
                    LocalDate.now(ZoneOffset.UTC).plusDays(1),
                    "Coordination",
                    BigDecimal.ZERO,
                    key()),
                "task")
            .task();
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> reschedules.review("olivia", e.requestId(), r.id(), review(e, "APPROVED"), "open"));
    tasks.complete(
        "marcus",
        task.id(),
        new ExitTaskModels.Complete("Done", BigDecimal.ZERO, key()),
        "completed");
    db.update("update identity_membership set role='MANAGER' where subject='sophia'");
    var colleague = people.profile(people.actor("amelia"), "E004");
    var assignment =
        people.submit(
            people.actor("amelia"),
            new Commands.Submit(
                "E004",
                "managerId",
                "E003",
                r.lastWorkingDate().plusDays(5),
                "Future reporting",
                colleague.version(),
                key()),
            "reporting");
    people.decide(
        people.actor("olivia"),
        assignment.id(),
        new Commands.Decide("APPROVED", "Independent"),
        "approved");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            reschedules.review("olivia", e.requestId(), r.id(), review(e, "APPROVED"), "reports"));
  }

  @Test
  void sqlGuardsIndependentDecisionsAndImmutableHistory() {
    var e = schedule();
    var r =
        reschedules.submit(
            "amelia", e.requestId(), command(e, e.lastWorkingDate().plusDays(5)), "submitted");
    db.execute("savepoint invalid_reschedule");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "insert into hr_end_reschedule_decision(tenant,reschedule_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id,idempotency_key) values('atlas',?,'REJECTED','amelia','E001','Self review',?,now(),'forbidden',?)",
                r.id(),
                r.inputVersion(),
                key()));
    db.execute("rollback to savepoint invalid_reschedule");
    reschedules.review("olivia", e.requestId(), r.id(), review(e, "APPROVED"), "approved");
    for (var table :
        List.of("hr_end_reschedule", "hr_end_reschedule_decision", "hr_end_reschedule_handoff")) {
      db.execute("savepoint immutable_reschedule");
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> db.update("delete from " + table));
      db.execute("rollback to savepoint immutable_reschedule");
    }
    db.execute("set constraints all immediate");
  }

  @Test
  void matchingAuditMetadataDoesNotDuplicateCurrentHandoffs() {
    var original = schedule();
    var e = ends.context("amelia", original.requestId()).record();
    var fixed = at(e.recordedAt());
    var r =
        fixed.submit(
            "amelia", e.requestId(), command(e, e.lastWorkingDate().plusDays(5)), "scheduled");
    fixed.review("olivia", e.requestId(), r.id(), review(e, "APPROVED"), "scheduled");
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from hr_employment_end_handoff_current where employee_id='E003' and status='PENDING'",
            Integer.class));
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from hr_employment_end_handoff_current where employee_id='E003' and status='SUPPRESSED'",
            Integer.class));
    db.execute("set constraints all immediate");
  }

  @Test
  void replacementAttemptLimitFailsClosedWithoutLosingHistory() {
    var e = schedule();
    for (int i = 0; i < 20; i++) {
      var r =
          reschedules.submit(
              "amelia", e.requestId(), command(e, e.lastWorkingDate().plusDays(5)), "attempt");
      reschedules.review("olivia", e.requestId(), r.id(), review(e, "REJECTED"), "rejected");
    }
    assertEquals(20, reschedules.context("amelia", e.requestId()).history().size());
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            reschedules.submit(
                "amelia", e.requestId(), command(e, e.lastWorkingDate().plusDays(5)), "limit"));
    assertEquals(e, ends.context("amelia", e.requestId()).currentRecord());
  }
}
