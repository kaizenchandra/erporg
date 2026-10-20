package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.in.*;
import com.atlas.modules.benefits.application.port.out.*;
import com.atlas.modules.benefits.application.usecase.SelectionsService;
import com.atlas.modules.benefits.domain.model.SelectionPolicy;
import com.atlas.shared.domain.AccessDenied;
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
class BenefitInboxWorkflowTest {
  @Autowired SelectionInboxUseCases inbox;
  @Autowired SelectionsUseCases selections;
  @Autowired BenefitsUseCases benefits;
  @Autowired JdbcTemplate db;

  String key() {
    return UUID.randomUUID().toString();
  }

  SelectionView submit(String user, int number) {
    String code = "BG-PLAN-" + number;
    benefits.create(
        "amelia",
        new CreatePlan(
            code,
            "Synthetic BG plan " + number,
            "GBP",
            "15.50",
            "0",
            LocalDate.now(ZoneOffset.UTC),
            "Synthetic BG queue fixture",
            key()),
        "bg-plan");
    return selections.submit(
        user,
        new SubmitSelection(code, List.of(), "Synthetic BG selection " + number, key()),
        "bg-submit");
  }

  SelectionPage query(String user, String scope, String status, int page, int size) {
    return inbox.inbox(user, new SelectionInboxQuery(scope, status, page, size));
  }

  @Test
  void completeScopedPagesAndAliases() {
    var ids = new ArrayList<String>();
    for (int i = 0; i < 13; i++) ids.add(submit("sophia", i).id());
    var own = submit("amelia", 20);
    var manager = submit("marcus", 21);
    var first = query("amelia", "REVIEW", "PENDING", 0, 8);
    var second = query("amelia", "REVIEW", "PENDING", 1, 8);
    assertEquals(14, first.total());
    assertEquals(8, first.items().size());
    assertEquals(6, second.items().size());
    var actual = new ArrayList<String>();
    actual.addAll(first.items().stream().map(SelectionView::id).toList());
    actual.addAll(second.items().stream().map(SelectionView::id).toList());
    assertEquals(
        db.queryForList(
            "select id from benefit_selection where tenant='atlas' and employee_id<>'E001' and requested_by<>'amelia' order by recorded_at desc,id desc",
            String.class),
        actual);
    assertFalse(actual.contains(own.id()));
    assertTrue(actual.contains(manager.id()));
    assertEquals(15, query("olivia", "REVIEW", "ALL", 0, 50).total());
    assertEquals(13, query("sophia", "OWN", "ALL", 0, 50).total());
    assertEquals(1, query("marcus", "OWN", "ALL", 0, 50).total());
    assertEquals(0, query("outsider", "OWN", "ALL", 0, 50).total());
    db.update(
        "insert into identity_membership values('bg-alias','atlas','E003','Synthetic HR alias','HR')");
    assertEquals(2, query("bg-alias", "REVIEW", "ALL", 0, 50).total());
    assertEquals(13, query("bg-alias", "OWN", "ALL", 0, 50).total());
    assertEquals(0, query("bg-alias", "REVIEW", "ALL", 100000, 50).items().size());
  }

  @Test
  void terminalFiltersRetainAuditsAndReconcileHandoffs() {
    var approved = submit("sophia", 1);
    var rejected = submit("sophia", 2);
    var withdrawn = submit("sophia", 3);
    selections.decide(
        "amelia",
        approved.id(),
        new DecideSelection(0L, "APPROVED", "Synthetic independent approval", key()),
        "bg-approved");
    selections.decide(
        "amelia",
        rejected.id(),
        new DecideSelection(0L, "REJECTED", "Synthetic independent rejection", key()),
        "bg-rejected");
    selections.withdraw(
        "sophia",
        withdrawn.id(),
        new WithdrawSelection(java.math.BigDecimal.ZERO, "Synthetic withdrawal", key()),
        "bg-withdrawn");
    for (String status : List.of("APPROVED", "REJECTED", "WITHDRAWN")) {
      var result = query("amelia", "REVIEW", status, 0, 8);
      assertEquals(1, result.total());
      assertEquals(status, result.items().getFirst().status());
      assertEquals(2, result.items().getFirst().history().size());
    }
    assertEquals(0, query("amelia", "REVIEW", "PENDING", 0, 8).total());
    assertEquals(3, query("sophia", "OWN", "ALL", 0, 8).total());
    assertEquals(
        1, db.queryForObject("select count(*) from benefit_deduction_handoff", Integer.class));
  }

  @Test
  void currentTenantRoleRequesterAndEmployeeIndependence() {
    var request = submit("sophia", 1);
    for (String user : List.of("sophia", "marcus", "outsider"))
      assertThrows(AccessDenied.class, () -> query(user, "REVIEW", "PENDING", 0, 8));
    db.update("update identity_membership set employee_id='E004',role='HR' where subject='sophia'");
    assertEquals(0, query("sophia", "REVIEW", "ALL", 0, 8).total());
    assertEquals(0, query("sophia", "OWN", "ALL", 0, 8).total());
    assertThrows(
        AccessDenied.class,
        () ->
            selections.decide(
                "sophia",
                request.id(),
                new DecideSelection(0L, "REJECTED", "Cannot self review after move", key()),
                "denied"));
    db.update(
        "insert into identity_membership values('bg-alias','atlas','E003','Synthetic HR alias','HR')");
    assertEquals(0, query("bg-alias", "REVIEW", "ALL", 0, 8).total());
    assertThrows(
        AccessDenied.class,
        () ->
            selections.decide(
                "bg-alias",
                request.id(),
                new DecideSelection(0L, "REJECTED", "Cannot review own employee", key()),
                "denied"));
    db.update("update identity_membership set role='EMPLOYEE' where subject='amelia'");
    assertThrows(AccessDenied.class, () -> query("amelia", "REVIEW", "PENDING", 0, 8));
    db.update("update identity_membership set role='HR' where subject='outsider'");
    assertEquals(0, query("outsider", "REVIEW", "ALL", 0, 8).total());
  }

  @Test
  void exactBoundsAndDomainIndependence() {
    for (var q : List.of(new String[] {"BAD", "PENDING"}, new String[] {"OWN", "BAD"}))
      assertThrows(IllegalArgumentException.class, () -> new SelectionInboxQuery(q[0], q[1], 0, 8));
    assertThrows(
        IllegalArgumentException.class, () -> new SelectionInboxQuery(null, "PENDING", 0, 8));
    assertThrows(
        IllegalArgumentException.class, () -> new SelectionInboxQuery("OWN", "PENDING", -1, 8));
    assertThrows(
        IllegalArgumentException.class, () -> new SelectionInboxQuery("OWN", "PENDING", 100001, 8));
    assertThrows(
        IllegalArgumentException.class, () -> new SelectionInboxQuery("OWN", "PENDING", 0, 51));
    assertThrows(
        IllegalArgumentException.class, () -> new SelectionInboxQuery("OWN", "PENDING", 0, 0));
    assertFalse(SelectionPolicy.independent("E003", "other", "E003", "sophia"));
    assertFalse(SelectionPolicy.independent("E004", "sophia", "E003", "sophia"));
    assertTrue(SelectionPolicy.independent("E001", "amelia", "E003", "sophia"));
  }

  @Test
  void decisionActorResolvedInsideTransactionBeforeStateAccess() {
    var entered = new java.util.concurrent.atomic.AtomicBoolean();
    WorkforcePort workforce =
        new WorkforcePort() {
          public BenefitContext self(String subject) {
            return new BenefitContext(
                subject,
                "atlas",
                "E001",
                entered.get() ? "EMPLOYEE" : "HR",
                "Active",
                LocalDate.of(2020, 1, 1));
          }

          public BenefitContext employee(String subject, String employee) {
            throw new AssertionError();
          }
        };
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> action) {
            entered.set(true);
            return action.get();
          }
        };
    var service = new SelectionsService(null, workforce, tx, Clock.systemUTC());
    assertThrows(
        AccessDenied.class,
        () ->
            service.decide(
                "fixture",
                "unused",
                new DecideSelection(0L, "APPROVED", "Synthetic boundary review", key()),
                "boundary"));
  }
}
