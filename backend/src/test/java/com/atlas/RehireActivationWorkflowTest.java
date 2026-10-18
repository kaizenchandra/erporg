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
  @Autowired RehireAccessUseCases access;
  @Autowired IdentityReplacementUseCases replacements;
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
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from hr_employment_episode_state p join hr_rehire_completion c on c.tenant=p.tenant and c.employee_id=p.employee_id and c.rehire_id=p.rehire_id where p.tenant='atlas' and p.employee_id='E003' and p.episode_number=2 and p.inactive_date is null and p.completion_effective_date=c.effective_date",
            Integer.class));
    assertEquals(
        0,
        db.queryForObject(
            "select count(*) from hr_employment_episode_state where tenant='atlas' and employee_id='E003' and episode_number=1 and completion_effective_date is not null",
            Integer.class));
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

  void activated() {
    rehired(LocalDate.now(ZoneOffset.UTC));
    var c = command();
    var r = activations.submit("amelia", "E003", c, "request");
    activations.review("olivia", "E003", r.id(), review("APPROVED"), "approve");
  }

  com.atlas.modules.corehr.application.model.RehireAccessModels.Revoke revokeCommand() {
    var c = access.context("amelia", "E003");
    return new com.atlas.modules.corehr.application.model.RehireAccessModels.Revoke(
        c.activationId(),
        "Security review requires immediate local suspension",
        BigDecimal.valueOf(c.employeeVersion()),
        key());
  }

  @Test
  void accessRevocationIsTerminalAuditedAndNeverFallsBackToLegacyHr() {
    activated();
    db.update("update identity_membership set role='HR' where subject='sophia'");
    var c = revokeCommand();
    var oldEpoch = people.sessionEpoch("sophia");
    var r = access.revoke("amelia", "E003", c, "revoke-correlation");
    assertEquals(r, access.revoke("amelia", "E003", c, "replay"));
    assertEquals(c.version().longValue() + 1, r.newVersion());
    assertEquals("REVOKED", access.context("olivia", "E003").accessStatus());
    assertEquals("Active", people.profile(people.actor("amelia"), "E003").status());
    assertEquals("ACTIVE", rehires.context("amelia", "E003").episodes().get(1).state());
    assertThrows(ChangeRequest.Denied.class, () -> people.actor("sophia"));
    assertThrows(ChangeRequest.Denied.class, () -> people.sessionEpoch("sophia"));
    assertEquals(
        oldEpoch,
        db.queryForObject(
            "select epoch from hr_rehire_access where employee_id='E003'", String.class));
    assertEquals(
        "HR",
        db.queryForObject(
            "select role from identity_membership where subject='sophia'", String.class));
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from hr_rehire_handoff_current where target='IDENTITY_REVIEW' and status='LOCAL_ACCESS_REVOKED'",
            Integer.class));
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
    assertEquals(r, access.context("amelia", "E003").revocation());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> access.revoke("olivia", "E003", revokeCommand(), "again"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            access.revoke(
                "amelia",
                "E003",
                new RehireAccessModels.Revoke(
                    c.activationId(), "Changed reason", c.version(), c.idempotencyKey()),
                "conflict"));
    db.execute("set constraints all immediate");
  }

  @Test
  void accessRevocationRequiresFreshExactSnapshotAndCorrectGrant() {
    assertThrows(ChangeRequest.Conflict.class, () -> access.context("amelia", "E003"));
    activated();
    var c = revokeCommand();
    for (var v :
        Arrays.asList(
            (BigDecimal) null,
            new BigDecimal("0.5"),
            new BigDecimal("-1"),
            new BigDecimal("9223372036854775807")))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              access.revoke(
                  "amelia",
                  "E003",
                  new RehireAccessModels.Revoke(c.activationId(), c.reason(), v, key()),
                  "bad-version"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            access.revoke(
                "amelia",
                "E003",
                new RehireAccessModels.Revoke(c.activationId(), c.reason(), BigDecimal.ZERO, key()),
                "stale"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            access.revoke(
                "amelia",
                "E003",
                new RehireAccessModels.Revoke("other-grant", c.reason(), c.version(), key()),
                "wrong-grant"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            access.revoke(
                "amelia",
                "E003",
                new RehireAccessModels.Revoke(c.activationId(), " ", c.version(), key()),
                "blank"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            access.revoke(
                "amelia",
                "E003",
                new RehireAccessModels.Revoke(c.activationId(), c.reason(), c.version(), "bad"),
                "bad-key"));
    assertEquals("GRANTED", access.context("amelia", "E003").accessStatus());
  }

  @Test
  void accessRevocationCannotEscalateOrCrossTenantOrTargetAliases() {
    activated();
    var c = revokeCommand();
    for (var subject : List.of("sophia", "marcus", "outsider")) {
      assertThrows(ChangeRequest.Denied.class, () -> access.context(subject, "E003"));
      assertThrows(ChangeRequest.Denied.class, () -> access.revoke(subject, "E003", c, "denied"));
    }
    db.update("update identity_membership set employee_id='E003',role='HR' where subject='olivia'");
    assertThrows(ChangeRequest.Denied.class, () -> access.revoke("olivia", "E003", c, "alias"));
    assertThrows(ChangeRequest.Denied.class, () -> access.context("amelia", "E900"));
  }

  @Test
  void revokedGrantSqlSnapshotAndImmutabilityAreEnforced() {
    activated();
    var c = revokeCommand();
    db.execute(
        "DO $probe$ BEGIN BEGIN INSERT INTO hr_rehire_access_revocation(tenant,activation_id,employee_id,subject,input_version,new_version,actor,actor_employee_id,reason,recorded_at,correlation_id,idempotency_key) SELECT tenant,activation_id,employee_id,subject,0,1,'amelia','E001','invalid snapshot',clock_timestamp(),'probe','sql-probe-0001' FROM hr_rehire_access; RAISE EXCEPTION 'guard did not reject insert' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM<>'Revocation employee snapshot mismatch' THEN RAISE; END IF; END; END $probe$");
    access.revoke("amelia", "E003", c, "revoke");
    db.execute(
        "DO $probe$ BEGIN BEGIN UPDATE hr_rehire_access_revocation SET reason='altered' WHERE employee_id='E003'; RAISE EXCEPTION 'guard did not reject update' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM<>'Audit events are append only' THEN RAISE; END IF; END; END $probe$");
    db.execute(
        "DO $probe$ BEGIN BEGIN DELETE FROM hr_rehire_access_revocation WHERE employee_id='E003'; RAISE EXCEPTION 'guard did not reject delete' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM<>'Audit events are append only' THEN RAISE; END IF; END; END $probe$");
    assertEquals(
        1, db.queryForObject("select count(*) from hr_rehire_access_revocation", Integer.class));
  }

  @Test
  void revokedSubjectCannotReturnThroughMembershipReassignmentOrAnotherTenant() {
    activated();
    access.revoke("amelia", "E003", revokeCommand(), "revoke");
    db.update("update identity_membership set employee_id='E001',role='HR' where subject='sophia'");
    assertThrows(ChangeRequest.Denied.class, () -> people.actor("sophia"));
    assertThrows(ChangeRequest.Denied.class, () -> people.sessionEpoch("sophia"));
    assertThrows(ChangeRequest.Denied.class, () -> access.context("sophia", "E003"));
    db.update(
        "update identity_membership set tenant='other',employee_id='E900',role='HR' where subject='sophia'");
    assertThrows(ChangeRequest.Denied.class, () -> people.actor("sophia"));
    assertThrows(ChangeRequest.Denied.class, () -> people.sessionEpoch("sophia"));
    assertEquals("REVOKED", access.context("amelia", "E003").accessStatus());
  }

  RehireAccessRevocation revokedForReplacement() {
    activated();
    db.update("update identity_membership set employee_id='E003',role='HR' where subject='marcus'");
    return access.revoke("amelia", "E003", revokeCommand(), "original-revocation");
  }

  IdentityReplacementModels.Submit replacementCommand() {
    return new IdentityReplacementModels.Submit(
        "marcus",
        "Reviewed replacement for held identity",
        BigDecimal.valueOf(replacements.context("amelia", "E003").employeeVersion()),
        key());
  }

  IdentityReplacementModels.Review replacementReview(String decision) {
    return new IdentityReplacementModels.Review(
        decision,
        "Independent replacement identity review",
        BigDecimal.valueOf(replacements.context("amelia", "E003").employeeVersion()),
        key());
  }

  @Test
  void reviewedReplacementGrantsOnlyNewIdentityAndBothGrantsRemainRevocable() {
    activated();
    db.update("update identity_membership set employee_id='E003',role='HR' where subject='marcus'");
    var originalCommand = revokeCommand();
    var original = access.revoke("amelia", "E003", originalCommand, "original-revocation");
    var ctx = replacements.context("amelia", "E003");
    assertTrue(ctx.canRequest());
    assertTrue(ctx.candidates().contains("marcus"));
    assertFalse(ctx.candidates().contains("sophia"));
    var c = replacementCommand();
    var r = replacements.submit("amelia", "E003", c, "requested");
    assertEquals(r, replacements.submit("amelia", "E003", c, "replay"));
    assertThrows(ChangeRequest.Denied.class, () -> people.actor("marcus"));
    var d = replacementReview("APPROVED");
    var approved = replacements.review("olivia", "E003", r.id(), d, "approved");
    assertEquals(approved, replacements.review("olivia", "E003", r.id(), d, "replay"));
    assertEquals("EMPLOYEE", people.actor("marcus").role());
    assertEquals(1, people.summary(people.actor("marcus")).total());
    assertThrows(ChangeRequest.Denied.class, () -> people.actor("sophia"));
    assertEquals(2, access.context("amelia", "E003").grantNumber());
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from hr_access_grant_episode where tenant='atlas' and employee_id='E003' and episode_number=2",
            Integer.class));
    assertEquals(
        1,
        db.queryForObject(
            "select count(distinct rehire_id) from hr_access_grant_episode where tenant='atlas' and employee_id='E003'",
            Integer.class));
    assertEquals(
        2,
        db.queryForObject(
            "select grant_sequence from hr_episode_access_current where tenant='atlas' and employee_id='E003' and episode_number=2",
            Integer.class));
    assertEquals(r.id(), access.context("amelia", "E003").activationId());
    assertEquals(original, access.revoke("amelia", "E003", originalCommand, "late-replay"));
    assertEquals("GRANTED", access.context("amelia", "E003").accessStatus());
    assertEquals(
        "HR",
        db.queryForObject(
            "select role from identity_membership where subject='marcus'", String.class));
    var second = access.revoke("olivia", "E003", revokeCommand(), "replacement-revocation");
    assertEquals(r.id(), second.activationId());
    var retained = replacements.context("amelia", "E003");
    assertEquals(
        java.util.List.of("sophia", "marcus"),
        retained.grants().stream().map(g -> g.subject()).toList());
    assertEquals(java.util.List.of(original, second), retained.revocations());
    assertEquals(
        2, db.queryForObject("select count(*) from hr_rehire_access_revocation", Integer.class));
    assertThrows(ChangeRequest.Denied.class, () -> people.actor("marcus"));
    assertThrows(ChangeRequest.Denied.class, () -> people.actor("sophia"));
    assertFalse(replacements.context("amelia", "E003").canRequest());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> replacements.submit("amelia", "E003", replacementCommand(), "third"));
    assertEquals("Active", access.context("amelia", "E003").employmentStatus());
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from hr_rehire_handoff_current where target='IDENTITY_REVIEW' and status='LOCAL_ACCESS_REVOKED'",
            Integer.class));
    db.execute("set constraints all immediate");
  }

  @Test
  void replacementRequiresRevocationAndRejectsInvalidIdentityVersionsAndRoles() {
    activated();
    assertFalse(replacements.context("amelia", "E003").canRequest());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> replacements.submit("amelia", "E003", replacementCommand(), "not-revoked"));
    db.update("update identity_membership set employee_id='E003',role='HR' where subject='marcus'");
    access.revoke("amelia", "E003", revokeCommand(), "revoked");
    var c = replacementCommand();
    for (var subject : List.of("sophia", "amelia", "missing"))
      assertThrows(
          ChangeRequest.Validation.class,
          () ->
              replacements.submit(
                  "amelia",
                  "E003",
                  new IdentityReplacementModels.Submit(subject, c.reason(), c.version(), key()),
                  "bad-identity"));
    for (var v : Arrays.asList((BigDecimal) null, new BigDecimal("0.5"), new BigDecimal("-1")))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              replacements.submit(
                  "amelia",
                  "E003",
                  new IdentityReplacementModels.Submit(c.subject(), c.reason(), v, key()),
                  "bad-version"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            replacements.submit(
                "amelia",
                "E003",
                new IdentityReplacementModels.Submit(
                    c.subject(), c.reason(), BigDecimal.ZERO, key()),
                "stale"));
    for (var actor : List.of("sophia", "marcus", "outsider"))
      assertThrows(ChangeRequest.Denied.class, () -> replacements.context(actor, "E003"));
    assertThrows(ChangeRequest.Denied.class, () -> replacements.context("amelia", "E900"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            replacements.submit(
                "amelia",
                "E003",
                new IdentityReplacementModels.Submit(c.subject(), " ", c.version(), key()),
                "blank"));
  }

  @Test
  void replacementReviewRevalidatesSnapshotIdentityAndIndependentEmployeeWithRecovery() {
    revokedForReplacement();
    var c = replacementCommand();
    var r = replacements.submit("amelia", "E003", c, "request");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> replacements.review("amelia", "E003", r.id(), replacementReview("APPROVED"), "self"));
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) values('hr-alias','atlas','E001','HR alias','HR')");
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            replacements.review(
                "hr-alias", "E003", r.id(), replacementReview("APPROVED"), "alias"));
    db.update("update hr_employee set version=version+1 where tenant='atlas' and id='E003'");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            replacements.review("olivia", "E003", r.id(), replacementReview("APPROVED"), "stale"));
    replacements.review("olivia", "E003", r.id(), replacementReview("REJECTED"), "reject-stale");
    var fresh = replacements.submit("amelia", "E003", replacementCommand(), "fresh");
    db.update("update identity_membership set employee_id='E001' where subject='marcus'");
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            replacements.review(
                "olivia", "E003", fresh.id(), replacementReview("APPROVED"), "identity-moved"));
    replacements.review(
        "olivia", "E003", fresh.id(), replacementReview("REJECTED"), "reject-moved");
    assertEquals("REVOKED", access.context("amelia", "E003").accessStatus());
    assertEquals(1, db.queryForObject("select count(*) from hr_local_access_grant", Integer.class));
  }

  @Test
  void replacementCommandsBoundAttemptsAndReplayCannotAlterInputs() {
    revokedForReplacement();
    var c = replacementCommand();
    var first = replacements.submit("amelia", "E003", c, "request");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            replacements.submit(
                "amelia",
                "E003",
                new IdentityReplacementModels.Submit(
                    c.subject(), "Changed", c.version(), c.idempotencyKey()),
                "conflict"));
    var d = replacementReview("REJECTED");
    var reviewed = replacements.review("olivia", "E003", first.id(), d, "reject");
    assertEquals(reviewed, replacements.review("olivia", "E003", first.id(), d, "replay"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            replacements.review(
                "olivia",
                "E003",
                first.id(),
                new IdentityReplacementModels.Review(
                    "APPROVED", d.reason(), d.version(), d.idempotencyKey()),
                "conflicting-review"));
    for (int i = 1; i < 20; i++) {
      var r = replacements.submit("amelia", "E003", replacementCommand(), "attempt");
      replacements.review("olivia", "E003", r.id(), replacementReview("REJECTED"), "reject");
    }
    assertEquals(20, replacements.context("amelia", "E003").history().size());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> replacements.submit("amelia", "E003", replacementCommand(), "over-cap"));
  }

  @Test
  void registeredReplacementIdentityCannotFallbackAfterMappingMovesAndSqlHistoryIsImmutable() {
    revokedForReplacement();
    var r = replacements.submit("amelia", "E003", replacementCommand(), "request");
    replacements.review("olivia", "E003", r.id(), replacementReview("APPROVED"), "approve");
    db.update("update identity_membership set employee_id='E001',role='HR' where subject='marcus'");
    assertThrows(ChangeRequest.Denied.class, () -> people.actor("marcus"));
    assertThrows(ChangeRequest.Denied.class, () -> people.sessionEpoch("marcus"));
    db.update(
        "update identity_membership set tenant='other',employee_id='E900' where subject='marcus'");
    assertThrows(ChangeRequest.Denied.class, () -> people.actor("marcus"));
    for (var table :
        List.of(
            "hr_local_access_grant", "hr_identity_replacement", "hr_identity_replacement_decision"))
      db.execute(
          "DO $probe$ BEGIN BEGIN DELETE FROM "
              + table
              + "; RAISE EXCEPTION 'guard did not reject delete' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM<>'Audit events are append only' THEN RAISE; END IF; END; END $probe$");
    db.execute("set constraints all immediate");
  }

  @Test
  void replacementSqlRequiresApprovedDecisionAndAtomicGrant() {
    revokedForReplacement();
    var r = replacements.submit("amelia", "E003", replacementCommand(), "request");
    db.execute(
        "DO $probe$ BEGIN BEGIN INSERT INTO hr_local_access_grant(tenant,id,employee_id,subject,role,epoch,ordinal,predecessor_id,effective_date,recorded_at,correlation_id) SELECT tenant,id,employee_id,subject,'EMPLOYEE','unreviewed-epoch',2,predecessor_id,current_date,clock_timestamp(),'probe' FROM hr_identity_replacement WHERE id='"
            + r.id()
            + "'; RAISE EXCEPTION 'guard did not reject unreviewed grant' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM<>'Reviewed replacement grant required' THEN RAISE; END IF; END; END $probe$");
    db.execute(
        "DO $probe$ BEGIN BEGIN UPDATE hr_employee SET version=version+1 WHERE tenant='atlas' AND id='E003'; INSERT INTO hr_identity_replacement_decision(tenant,replacement_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id,idempotency_key) SELECT tenant,id,'APPROVED','olivia','E005','Independent SQL approval',input_version,clock_timestamp(),'probe','sql-missing-grant-001' FROM hr_identity_replacement WHERE id='"
            + r.id()
            + "'; SET CONSTRAINTS replacement_grant_reconciled IMMEDIATE; RAISE EXCEPTION 'guard did not reject missing grant' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM<>'Replacement decision/grant mismatch' THEN RAISE; END IF; END; END $probe$");
    assertEquals("SUBMITTED", replacements.context("amelia", "E003").history().get(0).status());
    assertEquals(1, db.queryForObject("select count(*) from hr_local_access_grant", Integer.class));
  }

  @Test
  void grantOwnershipIsAutomaticExactImmutableAndSurvivesRevocation() {
    activated();
    var grant = access.context("amelia", "E003");
    var owner =
        db.queryForMap(
            "select * from hr_access_grant_episode where tenant='atlas' and grant_id=?",
            grant.activationId());
    assertEquals("E003", owner.get("employee_id"));
    assertEquals(2, owner.get("episode_number"));
    assertEquals(1, owner.get("grant_sequence"));
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from hr_access_grant_episode o join hr_rehire_activation a on a.tenant=o.tenant and a.id=o.grant_id and a.rehire_id=o.rehire_id join hr_employment_episode p on p.tenant=o.tenant and p.rehire_id=o.rehire_id and p.start_date=o.episode_start_date",
            Integer.class));
    for (var command :
        List.of(
            "update hr_access_grant_episode set episode_start_date=episode_start_date+1",
            "delete from hr_access_grant_episode"))
      db.execute(
          "DO $probe$ BEGIN BEGIN "
              + command
              + "; RAISE EXCEPTION 'mutation allowed' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM<>'Audit events are append only' THEN RAISE; END IF; END; END $probe$");
    db.execute(
        "DO $probe$ BEGIN BEGIN insert into hr_access_grant_episode select tenant,grant_id,employee_id,episode_number,rehire_id,episode_start_date+1,grant_sequence from hr_access_grant_episode; RAISE EXCEPTION 'forged owner allowed' USING ERRCODE='ZX001'; EXCEPTION WHEN raise_exception THEN IF SQLERRM<>'Exact grant episode provenance required' THEN RAISE; END IF; END; END $probe$");
    access.revoke("amelia", "E003", revokeCommand(), "revoked");
    assertEquals(
        owner,
        db.queryForMap(
            "select * from hr_access_grant_episode where tenant='atlas' and grant_id=?",
            grant.activationId()));
    assertThrows(ChangeRequest.Denied.class, () -> people.actor("sophia"));
    db.execute("set constraints all immediate");
  }

  @Test
  void authenticationNeverUsesAnOtherwiseValidGrantFromAnotherEpisode() {
    activated();
    assertEquals("EMPLOYEE", people.actor("sophia").role());
    db.execute("create schema grant_scope_fixture");
    db.execute("set local search_path to grant_scope_fixture,atlas_test");
    // Negative adapter fixture: valid grant and epoch, but deliberately wrong episode provenance.
    // Production tables and ownership triggers are unchanged; all DDL rolls back with the test.
    db.execute(
        "create view grant_scope_fixture.hr_episode_access_current as select g.*,p.episode_number,'unrelated-return'::varchar(40) rehire_id,p.episode_start_date,p.grant_sequence from atlas_test.hr_local_access_grant g join atlas_test.hr_access_grant_episode p on p.tenant=g.tenant and p.grant_id=g.id");
    var store = new JdbcPeopleStore(JdbcClient.create(db.getDataSource()));
    assertThrows(
        ChangeRequest.Denied.class, () -> store.actor("sophia", LocalDate.now(ZoneOffset.UTC)));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> store.sessionEpoch("sophia", LocalDate.now(ZoneOffset.UTC)));
    assertEquals("HR", store.actor("amelia", LocalDate.now(ZoneOffset.UTC)).role());
  }

  @Test
  void migrationRejectsUnreconciledLegacyGrantProvenance() throws Exception {
    db.execute("savepoint grant_migration_probe");
    db.execute("create schema grant_migration_fixture");
    db.execute("set local search_path to grant_migration_fixture");
    db.execute(
        "create table hr_local_access_grant(tenant varchar(80),id varchar(40),employee_id varchar(40),ordinal integer,predecessor_id varchar(40),primary key(tenant,id))");
    db.execute(
        "create table hr_rehire_activation(tenant varchar(80),id varchar(40),employee_id varchar(40),rehire_id varchar(40))");
    db.execute(
        "create table hr_employment_episode(tenant varchar(80),employee_id varchar(40),number integer,rehire_id varchar(40),start_date date,primary key(tenant,employee_id,number),unique(tenant,rehire_id))");
    db.execute("insert into hr_local_access_grant values('a','orphan','employee',1,null)");
    var sql =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/main/resources/db/migration/V46__access_grant_episode_ownership.sql"));
    var failure =
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.execute(sql));
    assertTrue(
        failure
            .getMostSpecificCause()
            .getMessage()
            .contains("Unreconciled grant episode provenance"));
    db.execute("rollback to savepoint grant_migration_probe");
  }

  String colleagueCase() {
    var employee = people.profile(people.actor("olivia"), "E005");
    var request =
        resignations.submit(
            "olivia",
            new ResignationCommands.Submit(
                LocalDate.now(ZoneOffset.UTC).plusDays(30),
                "Synthetic colleague departure",
                BigDecimal.valueOf(employee.version()),
                key()),
            "colleague-submit");
    resignations.review(
        "amelia",
        request.id(),
        new ResignationCommands.Review(
            "APPROVED", "Independent colleague review", BigDecimal.ZERO, key()),
        "colleague-approved");
    return request.id();
  }

  ExitTaskModels.Create assignment(String employee, long version) {
    return new ExitTaskModels.Create(
        employee,
        "Handover to returning colleague",
        "Record synthetic handover receipt",
        LocalDate.now(ZoneOffset.UTC).plusDays(10),
        "Coordinate colleague departure",
        BigDecimal.valueOf(version),
        key());
  }

  @Test
  void activeRehireReceivesAndCompletesColleagueTasksAndOriginalCaseStaysClosed() {
    activated();
    var id = colleagueCase();
    var created = tasks.create("amelia", id, assignment("E003", 0), "assigned");
    assertEquals("E003", created.task().assigneeId());
    assertTrue(
        tasks.mine("sophia", new ExitTaskModels.Query("OPEN", 0, 100)).items().stream()
            .anyMatch(i -> i.task().id().equals(created.task().id())));
    var completed =
        tasks.complete(
            "sophia",
            created.task().id(),
            new ExitTaskModels.Complete(
                "Receipt recorded by current Employee identity", BigDecimal.ZERO, key()),
            "completed");
    assertEquals("COMPLETED", completed.task().status());
    var second = tasks.create("amelia", id, assignment("E002", 1), "second-task");
    tasks.manage(
        "amelia",
        second.task().id(),
        new ExitTaskModels.Manage(
            "REASSIGN", "E003", "Use current returning Employee", BigDecimal.ZERO, key()),
        "reassigned");
    assertThrows(ChangeRequest.Denied.class, () -> tasks.history("marcus", second.task().id()));
    assertEquals(2, tasks.history("sophia", created.task().id()).size());
    var oldCase =
        db.queryForObject(
            "select request_id from hr_case_episode where tenant='atlas' and employee_id='E003'",
            String.class);
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> tasks.create("amelia", oldCase, assignment("E002", 0), "old-case-denied"));
    db.execute("set constraints all immediate");
  }

  @Test
  void revokedRehireCannotReceiveNewOrReassignedTasksDespiteMembershipAndActiveStatus() {
    activated();
    var id = colleagueCase();
    var task = tasks.create("amelia", id, assignment("E002", 0), "original-assignee");
    access.revoke("amelia", "E003", revokeCommand(), "revoked-before-assignment");
    assertEquals("Active", access.context("amelia", "E003").employmentStatus());
    var store = new JdbcPeopleStore(JdbcClient.create(db.getDataSource()));
    assertFalse(store.hasCurrentWorkspaceIdentity("atlas", "E003", LocalDate.now(ZoneOffset.UTC)));
    assertFalse(store.hasCurrentWorkspaceIdentity("other", "E003", LocalDate.now(ZoneOffset.UTC)));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> tasks.create("amelia", id, assignment("E003", 1), "denied-new"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            tasks.manage(
                "amelia",
                task.task().id(),
                new ExitTaskModels.Manage(
                    "REASSIGN",
                    "E003",
                    "Must remain denied after suspension",
                    BigDecimal.ZERO,
                    key()),
                "denied-reassignment"));
    assertEquals(1, tasks.forCase("amelia", id).items().size());
    assertTrue(tasks.managementHistory("amelia", task.task().id()).isEmpty());
    assertEquals("E002", tasks.forCase("amelia", id).items().getFirst().task().assigneeId());
  }
}
