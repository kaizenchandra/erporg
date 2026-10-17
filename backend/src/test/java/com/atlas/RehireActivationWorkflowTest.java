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
class RehireActivationWorkflowTest {
  @Autowired EmploymentEndUseCases ends;
  @Autowired RehireUseCases rehires;
  @Autowired RehireActivationUseCases activations;
  @Autowired OrganisationUseCases organisation;
  @Autowired ResignationUseCases resignations;
  @Autowired PeopleUseCases people;
  @Autowired ExitTaskUseCases tasks;
  @Autowired JdbcTemplate db;

  String key() {
    return UUID.randomUUID().toString();
  }

  EmploymentEnd former() {
    return former(true);
  }

  EmploymentEnd former(boolean resolvePending) {
    if (resolvePending)
      for (var request : people.requests(people.actor("amelia")))
        if (request.employeeId().equals("E003") && request.status().equals("SUBMITTED"))
          people.decide(
              people.actor("amelia"),
              request.id(),
              new Commands.Decide(
                  "REJECTED", "Resolve pre-existing synthetic request before rehire"),
              "resolved");
    var e = people.profile(people.actor("sophia"), "E003");
    var client = JdbcClient.create(db.getDataSource());
    var ps = new JdbcPeopleStore(client);
    var rs = new JdbcResignationStore(client);
    var tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> work) {
            return work.get();
          }
        };
    var clock =
        Clock.fixed(
            LocalDate.now(ZoneOffset.UTC).minusDays(20).atTime(12, 0).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC);
    var resign =
        new com.atlas.modules.corehr.application.usecase.ResignationService(rs, ps, tx, tx, clock);
    var end = new EmploymentEndService(new JdbcEmploymentEndStore(client), rs, ps, tx, tx, clock);
    var r =
        resign.submit(
            "sophia",
            new ResignationCommands.Submit(
                LocalDate.now(ZoneOffset.UTC).minusDays(10),
                "Historical synthetic departure",
                BigDecimal.valueOf(e.version()),
                key()),
            "submitted");
    resign.review(
        "amelia",
        r.id(),
        new ResignationCommands.Review("APPROVED", "Independent", BigDecimal.ZERO, key()),
        "approved");
    return end.confirm(
        "amelia",
        r.id(),
        new Confirm("Agreed prior end", BigDecimal.valueOf(e.version()), key()),
        "ended");
  }

  void rehired(LocalDate start) {
    former();
    var c =
        new RehireModels.Submit(
            start,
            "Returning specialist",
            "Engineering",
            "London",
            "E001",
            "Rehire",
            BigDecimal.valueOf(rehires.context("amelia", "E003").employeeVersion()),
            key());
    var r = rehires.submit("amelia", "E003", c, "rehire");
    rehires.review(
        "olivia",
        "E003",
        r.id(),
        new RehireModels.Review("APPROVED", "Independent", c.version(), key()),
        "rehired");
  }

  RehireActivationModels.Submit command() {
    return new RehireActivationModels.Submit(
        "sophia",
        "Confirm onboarding evidence and nominate one Employee identity",
        BigDecimal.valueOf(activations.context("amelia", "E003").employeeVersion()),
        key());
  }

  RehireActivationModels.Review review(String decision) {
    return new RehireActivationModels.Review(
        decision,
        "Independent onboarding and least-privilege access review",
        BigDecimal.valueOf(activations.context("amelia", "E003").employeeVersion()),
        key());
  }

  @Test
  void independentApprovalCreatesActiveCompletionAndOnlyOneFreshEmployeeGrant() {
    rehired(LocalDate.now(ZoneOffset.UTC));
    db.update("update identity_membership set role='HR' where subject='sophia'");
    db.update("update identity_membership set employee_id='E003',role='HR' where subject='marcus'");
    var c = command();
    var r = activations.submit("amelia", "E003", c, "requested");
    assertEquals(r, activations.submit("amelia", "E003", c, "replay"));
    var d = review("APPROVED");
    var result = activations.review("olivia", "E003", r.id(), d, "approved");
    assertEquals(result, activations.review("olivia", "E003", r.id(), d, "replay"));
    var ctx = activations.context("amelia", "E003");
    assertEquals("Active", ctx.employmentStatus());
    assertEquals("EMPLOYEE", ctx.completion().role());
    assertEquals("sophia", ctx.completion().subject());
    assertEquals(c.version().longValue() + 1, ctx.employeeVersion());
    assertEquals("EMPLOYEE", people.actor("sophia").role());
    assertEquals(1, people.summary(people.actor("sophia")).total());
    assertThrows(ChangeRequest.Denied.class, () -> people.actor("marcus"));
    assertThrows(ChangeRequest.Denied.class, () -> activations.context("sophia", "E003"));
    assertEquals(
        "HR",
        db.queryForObject(
            "select role from identity_membership where subject='sophia'", String.class));
    assertFalse(people.sessionEpoch("sophia").startsWith("original/"));
    assertEquals("ACTIVE", rehires.context("amelia", "E003").episodes().get(1).state());
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from hr_rehire_handoff_current where target='ONBOARDING' and status='COMPLETED'",
            Integer.class));
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from hr_rehire_handoff_current where target='PAYROLL' and status='PENDING'",
            Integer.class));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> activations.submit("amelia", "E003", command(), "duplicate"));
    db.execute("set constraints all immediate");
  }

  @Test
  void pendingRejectedAndReplayDoNotCompleteOrGrant() {
    rehired(LocalDate.now(ZoneOffset.UTC));
    var c = command();
    var r = activations.submit("amelia", "E003", c, "requested");
    assertNull(activations.context("amelia", "E003").completion());
    assertThrows(ChangeRequest.Denied.class, () -> people.actor("sophia"));
    activations.review("olivia", "E003", r.id(), review("REJECTED"), "rejected");
    assertEquals("Onboarding", activations.context("amelia", "E003").employmentStatus());
    assertEquals(0, db.queryForObject("select count(*) from hr_rehire_access", Integer.class));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            activations.submit(
                "amelia",
                "E003",
                new RehireActivationModels.Submit(
                    "sophia", "Different", c.version(), c.idempotencyKey()),
                "conflicting-key"));
    activations.submit("amelia", "E003", command(), "fresh");
  }

  @Test
  void futureStartOrUnapprovedRehireCannotComplete() {
    assertThrows(ChangeRequest.Conflict.class, () -> activations.context("amelia", "E003"));
    rehired(LocalDate.now(ZoneOffset.UTC).plusDays(1));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> activations.submit("amelia", "E003", command(), "too-early"));
  }

  @Test
  void roleTenantSelfAndReviewerAliasAreDenied() {
    rehired(LocalDate.now(ZoneOffset.UTC));
    var c = command();
    for (var user : List.of("sophia", "marcus", "outsider")) {
      assertThrows(ChangeRequest.Denied.class, () -> activations.context(user, "E003"));
      assertThrows(ChangeRequest.Denied.class, () -> activations.submit(user, "E003", c, "denied"));
    }
    var r = activations.submit("amelia", "E003", c, "requested");
    var review = review("APPROVED");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> activations.review("amelia", "E003", r.id(), review, "self"));
    db.update("update identity_membership set employee_id='E001',role='HR' where subject='marcus'");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> activations.review("marcus", "E003", r.id(), review, "alias"));
  }

  @Test
  void exactVersionsStaleSnapshotAndUnknownIdentityFailSafely() {
    rehired(LocalDate.now(ZoneOffset.UTC));
    var c = command();
    for (var v : Arrays.asList(null, new BigDecimal("0.5"), new BigDecimal("-1")))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              activations.submit(
                  "amelia",
                  "E003",
                  new RehireActivationModels.Submit("sophia", "Reason", v, key()),
                  "invalid"));
    for (var subject : Arrays.asList(null, "", "outsider", "amelia", "unconfigured-subject"))
      assertThrows(
          ChangeRequest.Validation.class,
          () ->
              activations.submit(
                  "amelia",
                  "E003",
                  new RehireActivationModels.Submit(subject, "Reason", c.version(), key()),
                  "identity"));
    var r = activations.submit("amelia", "E003", c, "requested");
    db.update("update hr_employee set version=version+1 where tenant='atlas' and id='E003'");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> activations.review("olivia", "E003", r.id(), review("APPROVED"), "stale"));
    activations.review("olivia", "E003", r.id(), review("REJECTED"), "rejected");
  }

  @Test
  void identityReassignmentAndManagerChangesAreRecheckedAtReview() {
    rehired(LocalDate.now(ZoneOffset.UTC));
    var r = activations.submit("amelia", "E003", command(), "requested");
    db.update("update identity_membership set employee_id='E004' where subject='sophia'");
    assertThrows(
        ChangeRequest.Validation.class,
        () -> activations.review("olivia", "E003", r.id(), review("APPROVED"), "remapped"));
    activations.review("olivia", "E003", r.id(), review("REJECTED"), "closed");
    db.update("update identity_membership set employee_id='E003' where subject='sophia'");
    var next = activations.submit("amelia", "E003", command(), "fresh");
    db.update(
        "update identity_membership set role='EMPLOYEE' where tenant='atlas' and employee_id='E001'");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            activations.review(
                "olivia",
                "E003",
                next.id(),
                new RehireActivationModels.Review(
                    "APPROVED",
                    "Independent",
                    BigDecimal.valueOf(people.profile(people.actor("olivia"), "E003").version()),
                    key()),
                "manager-changed"));
  }

  @Test
  void twentyAttemptsAndImmutableSqlHistoryAreEnforced() {
    rehired(LocalDate.now(ZoneOffset.UTC));
    for (int i = 0; i < 20; i++) {
      var r = activations.submit("amelia", "E003", command(), "requested");
      activations.review("olivia", "E003", r.id(), review("REJECTED"), "rejected");
    }
    assertEquals(20, activations.context("amelia", "E003").history().size());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> activations.submit("amelia", "E003", command(), "limit"));
  }

  @Test
  void sqlIndependentDecisionAndCompletionGrantReconcile() {
    rehired(LocalDate.now(ZoneOffset.UTC));
    var r = activations.submit("amelia", "E003", command(), "requested");
    db.execute("savepoint activation_guard");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "insert into hr_rehire_activation_decision(tenant,activation_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id,idempotency_key) values('atlas',?,'REJECTED','amelia','E001','Self',?,now(),'forbidden',?)",
                r.id(),
                r.inputVersion(),
                key()));
    db.execute("rollback to savepoint activation_guard");
    activations.review("olivia", "E003", r.id(), review("APPROVED"), "approved");
    for (var table :
        List.of(
            "hr_rehire_activation",
            "hr_rehire_activation_decision",
            "hr_rehire_completion",
            "hr_rehire_access")) {
      db.execute("savepoint activation_immutable");
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> db.update("delete from " + table));
      db.execute("rollback to savepoint activation_immutable");
    }
    db.execute("set constraints all immediate");
  }
}
