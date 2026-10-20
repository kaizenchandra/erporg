package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.port.in.PeopleUseCases;
import com.atlas.modules.payroll.application.port.in.TalentIntakeUseCases;
import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.application.port.in.TalentUseCases;
import com.atlas.modules.talent.domain.model.TalentModels.*;
import com.atlas.shared.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TalentWorkflowTest {
  static final String SCHEMA =
      "atlas_talent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://localhost:5438/atlas?currentSchema=" + SCHEMA);
    r.add("spring.flyway.default-schema", () -> SCHEMA);
    r.add("spring.flyway.schemas", () -> SCHEMA);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
  }

  @Autowired TalentUseCases talent;
  @Autowired PeopleUseCases people;
  @Autowired TalentIntakeUseCases payroll;
  @Autowired JdbcTemplate db;

  String key() {
    return UUID.randomUUID().toString();
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  BigDecimal v(long n) {
    return BigDecimal.valueOf(n);
  }

  Decision decision(long n, String action) {
    return new Decision(v(n), action, "Synthetic independent review", key());
  }

  Requisition draft() {
    return talent.requisition(
        "amelia",
        new RequisitionInput(
            "Talent test analyst",
            "Design",
            "London, UK",
            "E002",
            today(),
            "Synthetic vacancy",
            key()),
        "test");
  }

  CandidateInput candidate(long version, String id, String action) {
    return new CandidateInput(
        v(version),
        id,
        action,
        "Zoe Synthetic",
        key() + "@example.test",
        "+1 202 555 8888",
        today() + "T10:00:00Z",
        "Independent interview plan",
        "Synthetic offer 100 units; no payment",
        "Synthetic recruitment",
        key());
  }

  @Test
  void candidateCreatesOneApprovedEmployeeAndDependentOnboardingActivatesOnlyAfterCompletion() {
    var r = draft();
    var rid = r.meta().id();
    r = talent.requisitionAction("amelia", rid, decision(0, "SUBMIT"), "test");
    assertThrows(
        AccessDenied.class,
        () -> talent.requisitionAction("amelia", rid, decision(1, "APPROVE"), "self"));
    r = talent.requisitionAction("olivia", rid, decision(1, "APPROVE"), "review");
    r = talent.candidate("amelia", rid, candidate(r.meta().version(), null, "ADD"), "add");
    String cid = r.candidates().getFirst().id();
    for (String action : List.of("INTERVIEW", "OFFER", "ACCEPTED", "SEND_HIRE"))
      r = talent.candidate("amelia", rid, candidate(r.meta().version(), cid, action), "test");
    var pending = r;
    assertThrows(
        RuntimeException.class,
        () ->
            talent.candidate(
                "amelia", rid, candidate(pending.meta().version(), cid, "APPROVE_HIRE"), "self"));
    var approve = candidate(r.meta().version(), cid, "APPROVE_HIRE");
    r = talent.candidate("olivia", rid, approve, "hire");
    assertEquals(r, talent.candidate("olivia", rid, approve, "replay"));
    String employee = r.candidates().getFirst().employeeId();
    assertEquals("Onboarding", people.profile(people.actor("amelia"), employee).status());
    var plans = (Page<?>) talent.page("amelia", "onboarding", employee, 0);
    var p = (Onboarding) plans.items().getFirst();
    String pid = p.meta().id();
    p =
        talent.joining(
            "amelia",
            pid,
            new Joining(
                v(0),
                "FORM",
                null,
                "Synthetic joining acknowledgements",
                null,
                null,
                null,
                "Joining form",
                key()),
            "form");
    p =
        talent.joining(
            "amelia",
            pid,
            new Joining(
                v(1),
                "ADD_TASKS",
                null,
                null,
                List.of(new TaskInput("Verify equipment", "E001", List.of())),
                null,
                null,
                "Task setup",
                key()),
            "task");
    String first = p.tasks().getFirst().id();
    p =
        talent.joining(
            "amelia",
            pid,
            new Joining(
                v(2),
                "ADD_TASKS",
                null,
                null,
                List.of(new TaskInput("Joining orientation", "E001", List.of(first))),
                null,
                null,
                "Task dependency",
                key()),
            "task");
    String second = p.tasks().get(1).id();
    assertThrows(
        InputValidation.class,
        () ->
            talent.joining(
                "amelia",
                pid,
                new Joining(
                    v(3),
                    "COMPLETE_TASK",
                    second,
                    null,
                    null,
                    "Evidence",
                    null,
                    "Out of order",
                    key()),
                "denied"));
    assertThrows(
        InputValidation.class,
        () ->
            talent.joining(
                "amelia",
                pid,
                new Joining(v(3), "ACTIVATE", null, null, null, null, v(0), "Incomplete", key()),
                "denied"));
    p =
        talent.joining(
            "amelia",
            pid,
            new Joining(
                v(3),
                "COMPLETE_TASK",
                first,
                null,
                null,
                "Equipment received",
                null,
                "Verified",
                key()),
            "done");
    p =
        talent.joining(
            "amelia",
            pid,
            new Joining(
                v(4),
                "COMPLETE_TASK",
                second,
                null,
                null,
                "Orientation completed",
                null,
                "Verified",
                key()),
            "done");
    long ev = people.profile(people.actor("amelia"), employee).version();
    p =
        talent.joining(
            "amelia",
            pid,
            new Joining(
                v(5),
                "ACTIVATE",
                null,
                null,
                null,
                null,
                v(ev),
                "All joining tasks verified",
                key()),
            "activate");
    assertEquals("COMPLETED", p.meta().status());
    assertEquals("Active", people.profile(people.actor("amelia"), employee).status());
    assertEquals(
        0,
        db.queryForObject(
            "select count(*) from identity_membership where employee_id=?",
            Integer.class,
            employee));
  }

  @Test
  void employeeGoalsFeedbackReturnAndIndependentRatingHaveRealHistory() {
    var r =
        talent.review(
            "marcus",
            new ReviewInput(
                "E003",
                "Synthetic cycle",
                today(),
                today().plusDays(30),
                1,
                5,
                "Set review cycle",
                key()),
            "cycle");
    var id = r.meta().id();
    r =
        talent.reviewAction(
            "sophia",
            id,
            new ReviewAction(
                v(0),
                "GOAL",
                null,
                "Deliver accessible workflows",
                null,
                null,
                null,
                "Set goal",
                key()),
            "goal");
    String gid = r.goals().getFirst().id();
    r =
        talent.reviewAction(
            "marcus",
            id,
            new ReviewAction(
                v(1),
                "FEEDBACK",
                gid,
                null,
                "Good progress",
                null,
                null,
                "Manager feedback",
                key()),
            "feedback");
    r =
        talent.reviewAction(
            "sophia",
            id,
            new ReviewAction(
                v(2),
                "SUBMIT",
                null,
                null,
                null,
                "Completed the planned work",
                null,
                "Submit review",
                key()),
            "submit");
    assertThrows(
        AccessDenied.class,
        () ->
            talent.reviewAction(
                "sophia",
                id,
                new ReviewAction(
                    v(3), "APPROVE", null, null, null, null, 5, "Self approval", key()),
                "denied"));
    assertThrows(
        InputValidation.class,
        () ->
            talent.reviewAction(
                "marcus",
                id,
                new ReviewAction(
                    v(3), "APPROVE", null, null, null, null, 9, "Invalid rating", key()),
                "denied"));
    r =
        talent.reviewAction(
            "marcus",
            id,
            new ReviewAction(
                v(3), "RETURN", null, null, null, null, null, "Add explanation", key()),
            "return");
    r =
        talent.reviewAction(
            "sophia",
            id,
            new ReviewAction(
                v(4),
                "SUBMIT",
                null,
                null,
                null,
                "Completed goals with evidence",
                null,
                "Corrected review",
                key()),
            "resubmit");
    r =
        talent.reviewAction(
            "marcus",
            id,
            new ReviewAction(
                v(5), "APPROVE", null, null, null, null, 4, "Independent rating", key()),
            "approve");
    assertEquals("COMPLETED", r.meta().status());
    assertEquals(7, talent.history("sophia", "reviews", id, 0).size());
    assertThrows(AccessDenied.class, () -> talent.record("outsider", "reviews", id));
    assertThrows(RuntimeException.class, () -> talent.record("unknown-subject", "reviews", id));
  }

  @Test
  void learningRoleEligibilityCompletionAndExpirationArePreserved() {
    var c =
        talent.course(
            "amelia",
            new CourseInput(
                "Secure delivery",
                "Complete training and record evidence",
                "EMPLOYEE",
                30,
                "Publish training",
                key()),
            "course");
    assertThrows(
        InputValidation.class,
        () ->
            talent.assign(
                "amelia",
                new Assignment(c.meta().id(), "E002", today().plusDays(1), "Wrong role", key()),
                "denied"));
    var e =
        talent.assign(
            "marcus",
            new Assignment(c.meta().id(), "E003", today().plusDays(2), "Team training", key()),
            "assign");
    var id = e.meta().id();
    assertThrows(
        AccessDenied.class,
        () ->
            talent.learning(
                "marcus",
                id,
                new LearningAction(v(0), "COMPLETE", "Not my learning", "Denied", key()),
                "denied"));
    e =
        talent.learning(
            "sophia",
            id,
            new LearningAction(
                v(0), "COMPLETE", "Synthetic assessment completed", "Worker attestation", key()),
            "complete");
    assertEquals(today().plusDays(30), e.expires());
    assertEquals(30, e.validDays());
    assertEquals("COMPLETED", e.meta().status());
  }

  @Test
  void budgetApprovalCreatesOnePayrollOwnedReceiptAndNeverClaimsPayment() {
    var proposals = List.of(new ProposalInput("E003", "10000.00", "11000.00", "500.25", today()));
    assertThrows(
        InputValidation.class,
        () ->
            talent.compensation(
                "amelia",
                new CompensationInput(
                    "Over budget", "USD", "1500.24", proposals, "Synthetic plan", key()),
                "denied"));
    var p =
        talent.compensation(
            "amelia",
            new CompensationInput(
                "Within budget", "USD", "1500.25", proposals, "Synthetic plan", key()),
            "plan");
    var id = p.meta().id();
    assertEquals(new BigDecimal("1500.25"), p.total());
    p = talent.compensationAction("amelia", id, decision(0, "SUBMIT"), "submit");
    assertThrows(
        AccessDenied.class,
        () -> talent.compensationAction("amelia", id, decision(1, "APPROVE"), "self"));
    var action = decision(1, "APPROVE");
    p = talent.compensationAction("olivia", id, action, "approve");
    assertEquals(p, talent.compensationAction("olivia", id, action, "retry"));
    var input =
        payroll.pending("olivia", 0).stream()
            .filter(i -> i.sourceId().equals(id))
            .findFirst()
            .orElseThrow();
    assertEquals(p.payrollIntakeId(), input.id());
    assertEquals("RECEIVED", input.status());
    assertEquals(new BigDecimal("500.25"), input.awards().getFirst().bonus());
    assertThrows(AccessDenied.class, () -> payroll.pending("sophia", 0));
  }

  @Test
  void successionAndScenarioUseCurrentScopedWorkersAndExactIndependentCosts() {
    var s =
        talent.succession(
            "amelia",
            new SuccessionInput(
                "Engineering lead",
                List.of(
                    new NomineeInput(
                        "E003",
                        "Delivery and mentoring",
                        "WITHIN_ONE_YEAR",
                        "Demonstrated leadership")),
                "Talent review",
                key()),
            "succession");
    assertEquals("E003", s.nominees().getFirst().employeeId());
    var amended =
        talent.successionUpdate(
            "olivia",
            s.meta().id(),
            new SuccessionUpdate(
                v(0),
                "E003",
                "Delivery and coaching",
                "READY_NOW",
                "Updated reviewed assessment",
                "Talent review update",
                key()),
            "update");
    assertEquals(1, amended.meta().version());
    assertEquals("READY_NOW", amended.nominees().getFirst().readiness());
    assertEquals(2, talent.history("amelia", "succession", s.meta().id(), 0).size());
    assertThrows(AccessDenied.class, () -> talent.record("marcus", "succession", s.meta().id()));
    var q =
        talent.scenario(
            "amelia",
            new ScenarioInput(
                "Engineering growth",
                "USD",
                today(),
                List.of("E002", "E003"),
                List.of(new CostInput("Engineering", 2, 3, "12345.67")),
                "Planning assumptions",
                key()),
            "scenario");
    assertEquals(new BigDecimal("24691.34"), q.baselineCost());
    assertEquals(new BigDecimal("37037.01"), q.proposedCost());
    assertEquals(new BigDecimal("12345.67"), q.variance());
    assertThrows(
        InputValidation.class,
        () ->
            talent.scenario(
                "amelia",
                new ScenarioInput(
                    "Mismatch",
                    "USD",
                    today(),
                    List.of("E003"),
                    List.of(new CostInput("Design", 2, 3, "1.00")),
                    "Mismatch",
                    key()),
                "denied"));
  }

  @Test
  void stableReplayConflictAndConcurrencyHaveOneWinner() throws Exception {
    var c =
        new RequisitionInput(
            "Concurrency vacancy", "Design", "London", "E002", today(), "Synthetic vacancy", key());
    var p = talent.requisition("amelia", c, "create");
    assertEquals(p, talent.requisition("amelia", c, "replay"));
    assertThrows(
        CommandConflict.class,
        () ->
            talent.requisition(
                "amelia",
                new RequisitionInput(
                    "Different vacancy",
                    "Design",
                    "London",
                    "E002",
                    today(),
                    c.reason(),
                    c.idempotencyKey()),
                "conflict"));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var calls =
          List.of(
              (Callable<String>)
                  () -> {
                    try {
                      talent.requisitionAction(
                          "amelia", p.meta().id(), decision(0, "SUBMIT"), "race");
                      return "OK";
                    } catch (CommandConflict e) {
                      return "CONFLICT";
                    }
                  },
              () -> {
                try {
                  talent.requisitionAction("amelia", p.meta().id(), decision(0, "SUBMIT"), "race");
                  return "OK";
                } catch (CommandConflict e) {
                  return "CONFLICT";
                }
              });
      var outcomes = new ArrayList<String>();
      for (var f : pool.invokeAll(calls)) outcomes.add(f.get());
      Collections.sort(outcomes);
      assertEquals(List.of("CONFLICT", "OK"), outcomes);
    }
    assertThrows(
        InputValidation.class,
        () ->
            talent.requisitionAction(
                "amelia",
                p.meta().id(),
                new Decision(new BigDecimal("1.5"), "SUBMIT", "Fractional", key()),
                "invalid"));
  }

  @Test
  void unauthorizedCreationAndDirectSqlMutationCannotBypassHistory() {
    assertThrows(
        AccessDenied.class,
        () ->
            talent.requisition(
                "sophia",
                new RequisitionInput(
                    "Denied", "Design", "London", "E002", today(), "Denied", key()),
                "denied"));
    var r = draft();
    assertThrows(
        Exception.class,
        () ->
            db.update(
                "update tal_requisition set snapshot=jsonb_set(snapshot,'{meta,version}','9') where id=?",
                r.meta().id()));
    assertThrows(
        Exception.class, () -> db.update("delete from tal_requisition where id=?", r.meta().id()));
    assertThrows(
        Exception.class,
        () -> db.update("update tal_audit set snapshot=snapshot where record_id=?", r.meta().id()));
    assertEquals(1, talent.history("amelia", "requisitions", r.meta().id(), 0).size());
  }

  @Autowired tools.jackson.databind.json.JsonMapper httpJson;

  @Test
  void fractionalIntegerFieldsAreRejectedAtTheTransportBoundary() {
    assertThrows(
        RuntimeException.class,
        () -> httpJson.readValue("{\"minimumRating\":1.5,\"maximumRating\":5}", ReviewInput.class));
    assertThrows(
        RuntimeException.class, () -> httpJson.readValue("{\"validDays\":2.9}", CourseInput.class));
    assertThrows(
        RuntimeException.class,
        () ->
            httpJson.readValue(
                "{\"baselineHeadcount\":1.1,\"proposedHeadcount\":2,\"annualUnitCost\":\"1.00\"}",
                CostInput.class));
  }

  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

  @Test
  void registerOrdersRealInstantsAcrossVariablePrecisionRatherThanText() {
    var store = new com.atlas.modules.talent.adapter.out.persistence.JdbcTalentStore(jdbcClient);
    var older =
        new Course(
            new Stamp(
                key(), 0, "PUBLISHED", "amelia", "E001", Instant.parse("2050-01-01T00:00:00.123Z")),
            "Older precision fixture",
            "Synthetic timestamp ordering fixture",
            "ALL",
            0);
    var newer =
        new Course(
            new Stamp(
                key(),
                0,
                "PUBLISHED",
                "amelia",
                "E001",
                Instant.parse("2050-01-01T00:00:00.123001Z")),
            "Newer precision fixture",
            "Synthetic timestamp ordering fixture",
            "ALL",
            0);
    new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              for (var course : List.of(older, newer)) {
                store.insert("atlas", "courses", course.meta().id(), course);
                store.audit(
                    "atlas",
                    new Audit(
                        key(),
                        "courses",
                        course.meta().id(),
                        "amelia",
                        "E001",
                        "TIMESTAMP_FIXTURE",
                        "Synthetic precision fixture",
                        "",
                        store.encode(course),
                        course.meta().recordedAt(),
                        "time-order-test"));
              }
            });
    var page = store.page("atlas", "courses", "", 0, Course.class);
    assertEquals(newer.meta().id(), page.items().get(0).meta().id());
    assertEquals(older.meta().id(), page.items().get(1).meta().id());
  }
}
