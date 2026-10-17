package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
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
class ResignationWorkflowTest {
  @Autowired ResignationUseCases resignations;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;

  ResignationCommands.Submit command(String user) {
    return new ResignationCommands.Submit(
        LocalDate.now(ZoneOffset.UTC).plusDays(30),
        "Synthetic voluntary resignation",
        BigDecimal.valueOf(
            people.profile(people.actor(user), people.actor(user).employeeId()).version()),
        UUID.randomUUID().toString());
  }

  ResignationCommands.Review review(String decision) {
    return new ResignationCommands.Review(
        decision, "Independent review", BigDecimal.ZERO, UUID.randomUUID().toString());
  }

  @Test
  void approvalIsAtomicAuditedAndDoesNotTerminateOrRemoveAccess() {
    var c = command("sophia");
    var r = resignations.submit("sophia", c, "submit-correlation");
    assertEquals(r.id(), resignations.submit("sophia", c, "replay").id());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> resignations.submit("sophia", command("sophia"), "second"));
    var decision = review("APPROVED");
    var approved = resignations.review("amelia", r.id(), decision, "decision-correlation");
    assertEquals("APPROVED", approved.status());
    assertEquals(1, approved.version());
    assertEquals(approved, resignations.review("amelia", r.id(), decision, "replayed"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> resignations.review("olivia", r.id(), review("APPROVED"), "second-review"));
    assertEquals("Active", people.profile(people.actor("sophia"), "E003").status());
    assertEquals(
        c.employeeVersion().longValueExact(),
        people.profile(people.actor("sophia"), "E003").version());
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from hr_resignation_handoff where request_id=? and status='PENDING_EXIT_COORDINATION'",
            Integer.class,
            r.id()));
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from hr_notification where subject='sophia' and message like 'Resignation request approved%'",
            Integer.class));
    var history = resignations.history("sophia", r.id());
    assertEquals(2, history.size());
    assertNull(history.getFirst().oldStatus());
    assertEquals("decision-correlation", history.getLast().correlationId());
    db.execute("set constraints all immediate");
  }

  @Test
  void scopesAndSameEmployeeAliasCannotBypassIndependentReview() {
    var r = resignations.submit("amelia", command("amelia"), "self");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> resignations.review("amelia", r.id(), review("APPROVED"), "self-review"));
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) values('alias-hr','atlas','E001','Alias','HR')");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> resignations.review("alias-hr", r.id(), review("APPROVED"), "alias"));
    for (var user : List.of("sophia", "marcus", "outsider")) {
      assertEquals(0, resignations.list(user, new RequestQuery("ALL", 0, 1)).total());
      assertThrows(ChangeRequest.Denied.class, () -> resignations.history(user, r.id()));
      assertThrows(
          ChangeRequest.Denied.class,
          () -> resignations.review(user, r.id(), review("APPROVED"), "forbidden"));
    }
    assertEquals(1, resignations.list("olivia", new RequestQuery("SUBMITTED", 0, 1)).total());
    resignations.review("olivia", r.id(), review("REJECTED"), "rejected");
    assertEquals(
        0, db.queryForObject("select count(*) from hr_resignation_handoff", Integer.class));
    assertNotEquals(r.id(), resignations.submit("amelia", command("amelia"), "resubmit").id());
    db.execute("set constraints all immediate");
  }

  @Test
  void staleEmployeeBlocksApprovalButAllowsRejectionAndFreshSubmission() {
    var r = resignations.submit("sophia", command("sophia"), "submitted");
    db.update("update hr_employee set version=version+1 where tenant='atlas' and id='E003'");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> resignations.review("amelia", r.id(), review("APPROVED"), "stale"));
    resignations.review("amelia", r.id(), review("REJECTED"), "rejected");
    resignations.submit("sophia", command("sophia"), "fresh");
    db.execute("set constraints all immediate");
  }

  @Test
  void validationAndChangedReplayAreRejectedBeforeMutation() {
    var c = command("sophia");
    for (var v :
        Arrays.asList(
            null,
            new BigDecimal("0.5"),
            new BigDecimal("-1"),
            new BigDecimal("9999999999999999999999"))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              resignations.submit(
                  "sophia",
                  new ResignationCommands.Submit(
                      c.lastWorkingDate(), c.reason(), v, c.idempotencyKey()),
                  "invalid"));
    }
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            resignations.submit(
                "sophia",
                new ResignationCommands.Submit(
                    LocalDate.now(ZoneOffset.UTC).minusDays(1),
                    c.reason(),
                    c.employeeVersion(),
                    c.idempotencyKey()),
                "past"));
    var r = resignations.submit("sophia", c, "ok");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            resignations.submit(
                "sophia",
                new ResignationCommands.Submit(
                    c.lastWorkingDate(), "Changed", c.employeeVersion(), c.idempotencyKey()),
                "changed"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            resignations.review(
                "amelia",
                r.id(),
                new ResignationCommands.Review(
                    "APPROVED", " ", BigDecimal.ZERO, UUID.randomUUID().toString()),
                "blank"));
  }

  @Test
  void immutableSnapshotsAndAuditAreDatabaseEnforced() {
    var r = resignations.submit("sophia", command("sophia"), "submitted");
    db.execute("savepoint before_tamper");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("update hr_resignation set reason='tampered' where id=?", r.id()));
    db.execute("rollback to savepoint before_tamper");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("delete from hr_resignation_audit where request_id=?", r.id()));
    db.execute("rollback to savepoint before_tamper");
    assertEquals(1, resignations.history("sophia", r.id()).size());
  }

  @Test
  void delayedReplayRemainsStableButPastDateCannotBeApproved() {
    var c = command("sophia");
    var r = resignations.submit("sophia", c, "original");
    var client = org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource());
    com.atlas.modules.corehr.application.port.out.UnitOfWork tx =
        new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> work) {
            return work.get();
          }
        };
    var future =
        new com.atlas.modules.corehr.application.usecase.ResignationService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcResignationStore(client),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(client),
            tx,
            tx,
            Clock.fixed(
                c.lastWorkingDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                ZoneOffset.UTC));
    assertEquals(r.id(), future.submit("sophia", c, "delayed-replay").id());
    assertThrows(
        ChangeRequest.Validation.class,
        () -> future.review("amelia", r.id(), review("APPROVED"), "past-date"));
    future.review("amelia", r.id(), review("REJECTED"), "reject-old");
    db.execute("set constraints all immediate");
  }

  @Test
  void onLeaveEligibleButOnboardingAndRoleDowngradeAreDenied() {
    db.update("update hr_employee set status='On leave' where tenant='atlas' and id='E003'");
    var r = resignations.submit("sophia", command("sophia"), "on-leave");
    db.update("update identity_membership set role='EMPLOYEE' where subject='amelia'");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> resignations.review("amelia", r.id(), review("APPROVED"), "downgraded"));
    resignations.review("olivia", r.id(), review("REJECTED"), "rejected");
    db.update("update hr_employee set status='Onboarding' where tenant='atlas' and id='E003'");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> resignations.submit("sophia", command("sophia"), "onboarding"));
  }

  @Test
  void databaseRejectsApprovalWithoutMatchingAuditAndExitHandoff() {
    var r = resignations.submit("sophia", command("sophia"), "submitted");
    db.execute("savepoint incomplete_decision");
    db.update(
        "update hr_resignation set status='APPROVED',version=1,decided_by='amelia',reviewer_employee_id='E001',comment='Incomplete',decided_at=now(),decision_key='test-decision-key',decision_input_version=0 where tenant='atlas' and id=?",
        r.id());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.execute("set constraints all immediate"));
    db.execute("rollback to savepoint incomplete_decision");
    assertEquals(
        "SUBMITTED",
        resignations.list("sophia", new RequestQuery("ALL", 0, 12)).items().getFirst().status());
  }

  @Test
  void databaseRejectsAuditOnlyTerminalStatus() {
    var r = resignations.submit("sophia", command("sophia"), "submitted");
    db.execute("savepoint extra_audit");
    db.update(
        "insert into hr_resignation_audit(tenant,request_id,old_status,new_status,actor,reason,effective_date,recorded_at,correlation_id) values('atlas',?,'SUBMITTED','REJECTED','amelia','Unmatched terminal audit',?,now(),'forged-audit')",
        r.id(),
        r.lastWorkingDate());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.execute("set constraints all immediate"));
    db.execute("rollback to savepoint extra_audit");
    assertEquals(1, resignations.history("sophia", r.id()).size());
  }
}
