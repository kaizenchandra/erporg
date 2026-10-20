package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.in.*;
import com.atlas.modules.benefits.domain.model.*;
import com.atlas.shared.domain.*;
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
class BenefitWithdrawalWorkflowTest {
  @Autowired SelectionsUseCases selections;
  @Autowired BenefitsUseCases benefits;
  @Autowired JdbcTemplate db;

  String key() {
    return UUID.randomUUID().toString();
  }

  SubmitSelection input() {
    benefits.create(
        "amelia",
        new CreatePlan(
            "BEN-WITHDRAW",
            "Withdraw demo",
            "GBP",
            "12.50",
            "0",
            LocalDate.now(ZoneOffset.UTC),
            "Demonstration plan",
            key()),
        "created");
    return fresh();
  }

  SubmitSelection fresh() {
    return new SubmitSelection(
        "BEN-WITHDRAW",
        List.of(new Dependant("Synthetic Child", "CHILD")),
        "Synthetic benefit selection",
        key());
  }

  WithdrawSelection command() {
    return new WithdrawSelection(BigDecimal.ZERO, "Withdraw unreviewed selection", key());
  }

  @Test
  void withdrawalPreservesSnapshotsAndAuditReleasesSlotAndReplaysAfterNewSelection() {
    var input = input();
    var pending = selections.submit("sophia", input, "submitted");
    var c = command();
    var withdrawn = selections.withdraw("sophia", pending.id(), c, "withdraw-correlation");
    assertEquals("WITHDRAWN", withdrawn.status());
    assertEquals(1, withdrawn.version());
    assertNull(withdrawn.handoffStatus());
    assertEquals(pending.dependants(), withdrawn.dependants());
    assertEquals(pending.monthlyContribution(), withdrawn.monthlyContribution());
    assertEquals(pending.ruleVersion(), withdrawn.ruleVersion());
    assertEquals(2, withdrawn.history().size());
    assertEquals("withdraw-correlation", withdrawn.history().getLast().correlationId());
    assertEquals("WITHDRAWN", withdrawn.history().getLast().action());
    assertEquals(withdrawn, selections.withdraw("sophia", pending.id(), c, "repeat"));
    assertEquals(withdrawn, selections.submit("sophia", input, "submission-replay"));
    var next = selections.submit("sophia", fresh(), "new-request");
    assertNotEquals(pending.id(), next.id());
    assertEquals(withdrawn, selections.withdraw("sophia", pending.id(), c, "late-repeat"));
    assertEquals(2, selections.list("sophia", 0, 8).total());
    assertEquals(
        0, db.queryForObject("select count(*) from benefit_deduction_handoff", Integer.class));
    db.execute("set constraints all immediate");
  }

  @Test
  void withdrawalRequiresOriginalSubjectAndEmployeeAndExactVersion() {
    var p = selections.submit("sophia", input(), "submitted");
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) values('benefit-owner-alias','atlas','E003','Synthetic alias','EMPLOYEE')");
    for (var s : List.of("amelia", "marcus", "outsider", "benefit-owner-alias"))
      assertThrows(AccessDenied.class, () -> selections.withdraw(s, p.id(), command(), "denied"));
    db.update("update identity_membership set employee_id='E004',role='HR' where subject='sophia'");
    assertThrows(
        AccessDenied.class, () -> selections.withdraw("sophia", p.id(), command(), "moved-owner"));
    assertThrows(
        AccessDenied.class,
        () ->
            selections.decide(
                "sophia",
                p.id(),
                new DecideSelection(0L, "APPROVED", "Moved requester cannot approve", key()),
                "moved-review"));
    db.update(
        "update identity_membership set employee_id='E003',role='EMPLOYEE' where subject='sophia'");
    for (var version : Arrays.asList(null, new BigDecimal("0.5"), BigDecimal.valueOf(-1)))
      assertThrows(
          InputValidation.class,
          () ->
              selections.withdraw(
                  "sophia",
                  p.id(),
                  new WithdrawSelection(version, "Withdrawal reason", key()),
                  "invalid"));
    assertThrows(
        CommandConflict.class,
        () ->
            selections.withdraw(
                "sophia",
                p.id(),
                new WithdrawSelection(BigDecimal.ONE, "Withdrawal reason", key()),
                "stale"));
    assertThrows(
        InputValidation.class,
        () ->
            selections.withdraw(
                "sophia", p.id(), new WithdrawSelection(BigDecimal.ZERO, " ", key()), "invalid"));
    var c = command();
    selections.withdraw("sophia", p.id(), c, "withdrawn");
    assertThrows(
        CommandConflict.class,
        () ->
            selections.withdraw(
                "sophia",
                p.id(),
                new WithdrawSelection(BigDecimal.ZERO, "Different payload", c.idempotencyKey()),
                "conflict"));
    assertThrows(
        CommandConflict.class,
        () ->
            selections.decide(
                "amelia",
                p.id(),
                new DecideSelection(0L, "APPROVED", "Too late", key()),
                "late-review"));
  }

  @Test
  void approvedAndRejectedSelectionsCannotBeWithdrawn() {
    for (var outcome : List.of("REJECTED", "APPROVED")) {
      var c = outcome.equals("REJECTED") ? input() : fresh();
      var p = selections.submit("sophia", c, "submitted");
      selections.decide(
          "amelia",
          p.id(),
          new DecideSelection(0L, outcome, "Independent review", key()),
          "reviewed");
      assertThrows(
          CommandConflict.class, () -> selections.withdraw("sophia", p.id(), command(), "denied"));
    }
    assertEquals(
        1, db.queryForObject("select count(*) from benefit_deduction_handoff", Integer.class));
    db.execute("set constraints all immediate");
  }

  @Test
  void databaseRequiresOwnedTerminalTransitionAndCompleteAudit() {
    var p = selections.submit("sophia", input(), "submitted");
    db.execute("set constraints all immediate");
    db.execute("savepoint forged_owner");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "update benefit_selection set status='WITHDRAWN',version=1,decided_by='amelia',decision_key='forged-withdrawal',decision_reason='Forged withdrawal' where id=?",
                p.id()));
    db.execute("rollback to savepoint forged_owner");
    db.execute("set constraints all deferred");
    db.execute("savepoint incomplete_withdrawal");
    db.update(
        "update benefit_selection set status='WITHDRAWN',version=1,decided_by='sophia',decision_key='missing-audit-withdrawal',decision_reason='Missing audit' where id=?",
        p.id());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.execute("set constraints all immediate"));
    db.execute("rollback to savepoint incomplete_withdrawal");
    selections.withdraw("sophia", p.id(), command(), "complete");
    db.execute("set constraints all immediate");
    db.execute("savepoint immutable_withdrawal");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "delete from benefit_selection_audit where selection_id=? and action='WITHDRAWN'",
                p.id()));
    db.execute("rollback to savepoint immutable_withdrawal");
  }
}
