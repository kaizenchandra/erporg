package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.in.*;
import com.atlas.modules.benefits.domain.model.*;
import com.atlas.shared.domain.*;
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
class BenefitSelectionWorkflowTest {
  @Autowired SelectionsUseCases selections;
  @Autowired BenefitsUseCases benefits;
  @Autowired JdbcTemplate jdbc;

  SubmitSelection input() {
    benefits.create(
        "amelia",
        new CreatePlan(
            "BEN-ENROL",
            "Selection demo",
            "GBP",
            "25.50",
            "0",
            LocalDate.now(ZoneOffset.UTC),
            "Demo plan",
            UUID.randomUUID().toString()),
        "create");
    return new SubmitSelection(
        "BEN-ENROL",
        List.of(new Dependant("Synthetic Child", "CHILD")),
        "Family cover",
        UUID.randomUUID().toString());
  }

  DecideSelection approve() {
    return new DecideSelection(
        0L, "APPROVED", "Independent eligibility review", UUID.randomUUID().toString());
  }

  @Test
  void approvedSnapshotAuditAndUniquePendingHandoff() {
    var c = input();
    var pending = selections.submit("sophia", c, "submit");
    assertEquals("PENDING", pending.status());
    assertEquals(pending, selections.submit("sophia", c, "replay"));
    var d = approve();
    var approved = selections.decide("amelia", pending.id(), d, "decision");
    assertEquals("APPROVED", approved.status());
    assertEquals("25.50", approved.monthlyContribution());
    assertEquals("PENDING_PAYROLL", approved.handoffStatus());
    assertEquals(2, approved.history().size());
    assertEquals(approved, selections.decide("amelia", pending.id(), d, "again"));
    assertEquals(
        1, jdbc.queryForObject("select count(*) from benefit_deduction_handoff", Integer.class));
    assertThrows(
        CommandConflict.class,
        () ->
            selections.submit(
                "sophia",
                new SubmitSelection(
                    c.planCode(), c.dependants(), c.reason(), UUID.randomUUID().toString()),
                "second"));
  }

  @Test
  void privateScopesAndSelfReviewAlias() {
    var c = input();
    var pending = selections.submit("sophia", c, "submit");
    assertEquals(0, selections.list("marcus", 0, 8).total());
    assertEquals(0, selections.list("outsider", 0, 8).total());
    assertEquals(1, selections.list("amelia", 0, 8).total());
    for (var subject : List.of("sophia", "marcus", "outsider"))
      assertThrows(
          AccessDenied.class, () -> selections.decide(subject, pending.id(), approve(), "denied"));
    var own =
        selections.submit(
            "amelia",
            new SubmitSelection(c.planCode(), List.of(), "Own cover", UUID.randomUUID().toString()),
            "own");
    jdbc.update(
        "insert into identity_membership values('hr-alias','atlas','E001','Synthetic Alias','HR')");
    assertThrows(
        AccessDenied.class, () -> selections.decide("hr-alias", own.id(), approve(), "alias"));
    assertEquals(
        "APPROVED", selections.decide("olivia", own.id(), approve(), "independent").status());
  }

  @Test
  void rejectionAllowsNewSelectionAndConflictingReplayFails() {
    var c = input();
    var pending = selections.submit("sophia", c, "submit");
    assertThrows(
        CommandConflict.class,
        () ->
            selections.submit(
                "sophia",
                new SubmitSelection(c.planCode(), List.of(), c.reason(), c.idempotencyKey()),
                "changed"));
    var d =
        new DecideSelection(
            0L, "REJECTED", "More policy detail needed", UUID.randomUUID().toString());
    var rejected = selections.decide("amelia", pending.id(), d, "reject");
    assertNull(rejected.handoffStatus());
    assertThrows(
        CommandConflict.class,
        () ->
            selections.decide(
                "amelia",
                pending.id(),
                new DecideSelection(0L, "APPROVED", d.reason(), d.idempotencyKey()),
                "conflict"));
    assertEquals(
        "PENDING",
        selections
            .submit(
                "sophia",
                new SubmitSelection(
                    c.planCode(), c.dependants(), c.reason(), UUID.randomUUID().toString()),
                "replacement")
            .status());
  }

  @Test
  void recheckEligibilityAndVersionBeforeApproval() {
    var c = input();
    var p = selections.submit("sophia", c, "submit");
    assertThrows(
        InputValidation.class,
        () ->
            selections.decide(
                "amelia",
                p.id(),
                new DecideSelection(
                    null, "APPROVED", "Missing version", UUID.randomUUID().toString()),
                "missing"));
    assertThrows(
        CommandConflict.class,
        () ->
            selections.decide(
                "amelia",
                p.id(),
                new DecideSelection(99L, "APPROVED", "Stale version", UUID.randomUUID().toString()),
                "stale"));
    jdbc.update("update hr_employee set status='Onboarding' where tenant='atlas' and id='E003'");
    assertThrows(
        CommandConflict.class, () -> selections.decide("amelia", p.id(), approve(), "ineligible"));
    assertEquals(
        0, jdbc.queryForObject("select count(*) from benefit_deduction_handoff", Integer.class));
  }

  @Test
  void domainRejectsMalformedDependantsAndOversizedList() {
    assertThrows(InputValidation.class, () -> new Dependant("\n", "CHILD"));
    assertThrows(InputValidation.class, () -> new Dependant("Person", "UNKNOWN"));
    assertThrows(
        InputValidation.class,
        () -> SelectionPolicy.dependants(Collections.nCopies(6, new Dependant("Person", "CHILD"))));
    assertThrows(
        InputValidation.class,
        () ->
            SelectionPolicy.dependants(
                List.of(new Dependant("Person", "CHILD"), new Dependant("person", "OTHER"))));
  }

  @Test
  void databaseProtectsSnapshotAndDeductionIntegrity() {
    var p = selections.submit("sophia", input(), "submit");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update("update benefit_selection set monthly_contribution=1 where id=?", p.id()));
  }

  @Test
  void committedWorkflowMustReconcileAuditAndDeduction() {
    var pending = selections.submit("sophia", input(), "submit");
    selections.decide("amelia", pending.id(), approve(), "approve");
    assertDoesNotThrow(() -> jdbc.execute("set constraints all immediate"));
  }

  @Test
  void databaseRejectsTerminalStateWithoutDecisionAuditAndHandoff() {
    var pending = selections.submit("sophia", input(), "submit");
    jdbc.update(
        "update benefit_selection set status='APPROVED',version=1,decided_by='amelia',decision_key='raw-fixture',decision_reason='Invalid incomplete transaction' where id=?",
        pending.id());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.execute("set constraints all immediate"));
  }

  @Autowired com.atlas.modules.corehr.api.WorkforceAccess workforce;

  @Test
  void publishedEmploymentContractDoesNotExposeTeamRecordsToManagers() {
    assertEquals("E003", workforce.employee("sophia", "E003").employeeId());
    assertEquals("E003", workforce.employee("amelia", "E003").employeeId());
    assertThrows(AccessDenied.class, () -> workforce.employee("marcus", "E003"));
    assertThrows(AccessDenied.class, () -> workforce.employee("outsider", "E003"));
  }
}
