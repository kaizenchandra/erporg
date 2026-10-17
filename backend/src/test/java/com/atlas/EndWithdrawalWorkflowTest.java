package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.adapter.out.persistence.*;
import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.EmploymentEndModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.EmploymentEndService;
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
class EndWithdrawalWorkflowTest {
  @Autowired EmploymentEndUseCases ends;
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

  Confirm command(String id) {
    return new Confirm(
        "Employee continues employment",
        BigDecimal.valueOf(ends.context("amelia", id).employeeVersion()),
        key());
  }

  ReviewWithdrawal review(String id, String decision) {
    return new ReviewWithdrawal(
        decision,
        "Independent withdrawal review",
        BigDecimal.valueOf(ends.context("amelia", id).employeeVersion()),
        key());
  }

  EmploymentEndUseCases at(Instant instant) {
    var client = JdbcClient.create(db.getDataSource());
    var tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> work) {
            return work.get();
          }
        };
    return new EmploymentEndService(
        new JdbcEmploymentEndStore(client),
        new JdbcResignationStore(client),
        new JdbcPeopleStore(client),
        tx,
        tx,
        Clock.fixed(instant, ZoneOffset.UTC));
  }

  @Test
  void pendingAndRejectedPreserveCutoffButIndependentApprovalSuppressesWithoutErasingHistory() {
    var e = schedule();
    var c = command(e.requestId());
    var w = ends.withdraw("amelia", e.requestId(), c, "withdrawal-submitted");
    assertEquals("SUBMITTED", w.status());
    assertEquals(w, ends.withdraw("amelia", e.requestId(), c, "retry"));
    assertEquals(
        "EFFECTIVE",
        at(e.inactiveDate().atStartOfDay(ZoneOffset.UTC).toInstant())
            .context("amelia", e.requestId())
            .phase());
    var reject = review(e.requestId(), "REJECTED");
    ends.reviewWithdrawal("olivia", e.requestId(), w.id(), reject, "rejected");
    assertEquals("SCHEDULED", ends.context("amelia", e.requestId()).phase());
    var next = ends.withdraw("amelia", e.requestId(), command(e.requestId()), "resubmitted");
    var approve = review(e.requestId(), "APPROVED");
    var result = ends.reviewWithdrawal("olivia", e.requestId(), next.id(), approve, "approved");
    assertEquals(
        result, ends.reviewWithdrawal("olivia", e.requestId(), next.id(), approve, "retry"));
    assertEquals("WITHDRAWN", ends.context("amelia", e.requestId()).phase());
    assertEquals(e, ends.context("amelia", e.requestId()).record());
    var store = new JdbcPeopleStore(JdbcClient.create(db.getDataSource()));
    assertEquals(
        "Active", store.employee(people.actor("amelia"), "E003", e.inactiveDate(), false).status());
    assertEquals("E003", store.actor("sophia", e.inactiveDate()).employeeId());
    assertTrue(store.employmentEndDate("atlas", "E003").isEmpty());
    assertEquals(e.newVersion() + 1, people.profile(people.actor("amelia"), "E003").version());
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from hr_employment_end_handoff_current where employee_id='E003' and status='SUPPRESSED'",
            Integer.class));
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from hr_employment_end_handoff where employee_id='E003' and status='PENDING'",
            Integer.class));
    db.execute("set constraints all immediate");
  }

  @Test
  void roleTenantSelfAndAliasCannotRequestOrReview() {
    var e = schedule();
    var c = command(e.requestId());
    for (var user : List.of("sophia", "marcus", "outsider"))
      assertThrows(
          ChangeRequest.Denied.class, () -> ends.withdraw(user, e.requestId(), c, "denied"));
    var w = ends.withdraw("amelia", e.requestId(), c, "submitted");
    var r = review(e.requestId(), "APPROVED");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> ends.reviewWithdrawal("amelia", e.requestId(), w.id(), r, "self"));
    db.update("update identity_membership set employee_id='E001',role='HR' where subject='marcus'");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> ends.reviewWithdrawal("marcus", e.requestId(), w.id(), r, "alias"));
    db.update("update identity_membership set employee_id='E003',role='HR' where subject='olivia'");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> ends.reviewWithdrawal("olivia", e.requestId(), w.id(), r, "target"));
    assertThrows(
        ChangeRequest.Denied.class, () -> ends.withdraw("olivia", e.requestId(), c, "target"));
  }

  @Test
  void exactCutoffPreventsApprovalAndNewRequestsButAllowsRejection() {
    var e = schedule();
    var w = ends.withdraw("amelia", e.requestId(), command(e.requestId()), "submitted");
    var cutoff = at(e.inactiveDate().atStartOfDay(ZoneOffset.UTC).toInstant());
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            cutoff.reviewWithdrawal(
                "olivia", e.requestId(), w.id(), review(e.requestId(), "APPROVED"), "too-late"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> cutoff.withdraw("olivia", e.requestId(), command(e.requestId()), "too-late"));
    cutoff.reviewWithdrawal(
        "olivia", e.requestId(), w.id(), review(e.requestId(), "REJECTED"), "closed");
    assertEquals("EFFECTIVE", cutoff.context("amelia", e.requestId()).phase());
  }

  @Test
  void oneNanosecondBeforeCutoffCanApprove() {
    var e = schedule();
    var w = ends.withdraw("amelia", e.requestId(), command(e.requestId()), "submitted");
    var cutoff = at(e.inactiveDate().atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1));
    cutoff.reviewWithdrawal(
        "olivia", e.requestId(), w.id(), review(e.requestId(), "APPROVED"), "before-cutoff");
    assertEquals("WITHDRAWN", cutoff.context("amelia", e.requestId()).phase());
  }

  @Test
  void exactVersionAndStableKeysAreEnforced() {
    var e = schedule();
    var c = command(e.requestId());
    for (var v : Arrays.asList(null, new BigDecimal("0.5"), new BigDecimal("-1")))
      assertThrows(
          IllegalArgumentException.class,
          () -> ends.withdraw("amelia", e.requestId(), new Confirm("Reason", v, key()), "invalid"));
    var w = ends.withdraw("amelia", e.requestId(), c, "submitted");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            ends.withdraw(
                "amelia",
                e.requestId(),
                new Confirm("Changed", c.version(), c.idempotencyKey()),
                "conflict"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> ends.withdraw("olivia", e.requestId(), command(e.requestId()), "second-pending"));
    db.update("update hr_employee set version=version+1 where tenant='atlas' and id='E003'");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            ends.reviewWithdrawal(
                "olivia",
                e.requestId(),
                w.id(),
                review(e.requestId(), "APPROVED"),
                "stale-snapshot"));
    ends.reviewWithdrawal(
        "olivia", e.requestId(), w.id(), review(e.requestId(), "REJECTED"), "reject-stale");
  }

  @Test
  void approvalReopensCoordinationWithoutAllowingRescheduling() {
    var e = schedule();
    var w = ends.withdraw("amelia", e.requestId(), command(e.requestId()), "submitted");
    ends.reviewWithdrawal(
        "olivia", e.requestId(), w.id(), review(e.requestId(), "APPROVED"), "approved");
    var task =
        tasks.create(
            "amelia",
            e.requestId(),
            new ExitTaskModels.Create(
                "E002",
                "Follow-up",
                "Record follow-up",
                LocalDate.now(ZoneOffset.UTC).plusDays(1),
                "Coordination resumed",
                BigDecimal.ZERO,
                key()),
            "task");
    assertEquals("OPEN", task.task().status());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> ends.confirm("amelia", e.requestId(), command(e.requestId()), "reschedule"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> ends.withdraw("amelia", e.requestId(), command(e.requestId()), "already-withdrawn"));
    db.execute("set constraints all immediate");
  }

  @Test
  void sqlPreservesAppendOnlyRequestsAndDecisions() {
    var e = schedule();
    var w = ends.withdraw("amelia", e.requestId(), command(e.requestId()), "submitted");
    ends.reviewWithdrawal(
        "olivia", e.requestId(), w.id(), review(e.requestId(), "APPROVED"), "approved");
    for (var table : List.of("hr_end_withdrawal", "hr_end_withdrawal_decision")) {
      db.execute("savepoint immutable_withdrawal");
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> db.update("delete from " + table));
      db.execute("rollback to savepoint immutable_withdrawal");
    }
  }
}
