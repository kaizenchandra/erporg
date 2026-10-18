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

  ResignationCommands.Withdraw withdrawal(String reason) {
    return new ResignationCommands.Withdraw(reason, BigDecimal.ZERO, UUID.randomUUID().toString());
  }

  @Test
  void withdrawalPreservesSnapshotAndAccessAndFreesOutstandingSlot() {
    var original = command("sophia");
    var r = resignations.submit("sophia", original, "submitted");
    db.update("update hr_employee set version=version+1 where tenant='atlas' and id='E003'");
    var command = withdrawal("Changed personal plans");
    var receipt = resignations.withdraw("sophia", r.id(), command, "withdraw-correlation");
    assertEquals(receipt, resignations.withdraw("sophia", r.id(), command, "replay"));
    assertEquals(receipt, resignations.withdrawal("amelia", r.id()).orElseThrow());
    var saved =
        resignations.list("sophia", new RequestQuery("WITHDRAWN", 0, 12)).items().getFirst();
    assertEquals("WITHDRAWN", saved.status());
    assertEquals(r.reason(), saved.reason());
    assertNull(saved.decidedBy());
    assertNull(saved.comment());
    assertEquals(1, saved.version());
    assertEquals("Active", people.profile(people.actor("sophia"), "E003").status());
    assertEquals(
        original.employeeVersion().longValueExact() + 1,
        people.profile(people.actor("sophia"), "E003").version());
    assertEquals(
        0, db.queryForObject("select count(*) from hr_resignation_handoff", Integer.class));
    assertEquals(2, resignations.history("sophia", r.id()).size());
    assertEquals(
        "withdraw-correlation", resignations.history("sophia", r.id()).getLast().correlationId());
    var next = resignations.submit("sophia", command("sophia"), "fresh-request");
    assertNotEquals(r.id(), next.id());
    assertEquals(receipt, resignations.withdraw("sophia", r.id(), command, "late-replay"));
    assertEquals(r.id(), resignations.submit("sophia", original, "original-replay").id());
    assertEquals(1, resignations.list("sophia", new RequestQuery("SUBMITTED", 0, 12)).total());
    db.execute("set constraints all immediate");
  }

  @Test
  void withdrawalRequiresOriginalRequesterExactRequestVersionAndStablePayload() {
    var r = resignations.submit("sophia", command("sophia"), "submitted");
    for (var who : List.of("amelia", "olivia", "marcus", "outsider")) {
      assertThrows(
          ChangeRequest.Denied.class,
          () -> resignations.withdraw(who, r.id(), withdrawal("Forbidden"), "denied"));
    }
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) values('self-alias','atlas','E003','Alias','HR')");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> resignations.withdraw("self-alias", r.id(), withdrawal("Alias"), "denied"));
    for (var v : Arrays.asList(null, BigDecimal.valueOf(0.5), BigDecimal.valueOf(-1)))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              resignations.withdraw(
                  "sophia",
                  r.id(),
                  new ResignationCommands.Withdraw("Reason", v, UUID.randomUUID().toString()),
                  "bad"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            resignations.withdraw(
                "sophia",
                r.id(),
                new ResignationCommands.Withdraw(
                    "Reason", BigDecimal.ONE, UUID.randomUUID().toString()),
                "stale"));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> resignations.withdraw("sophia", r.id(), withdrawal(" "), "blank"));
    var c = withdrawal("Reason");
    resignations.withdraw("sophia", r.id(), c, "withdrawn");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            resignations.withdraw(
                "sophia",
                r.id(),
                new ResignationCommands.Withdraw("Changed", BigDecimal.ZERO, c.idempotencyKey()),
                "conflict"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> resignations.withdraw("sophia", r.id(), withdrawal("Reason"), "duplicate"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> resignations.review("amelia", r.id(), review("APPROVED"), "late-decision"));
    for (var who : List.of("marcus", "outsider"))
      assertThrows(ChangeRequest.Denied.class, () -> resignations.withdrawal(who, r.id()));
    db.execute("set constraints all immediate");
  }

  @Test
  void reviewedRequestsCannotBeWithdrawnOrHaveTheirHandoffsReversed() {
    for (var decision : List.of("REJECTED", "APPROVED")) {
      var r = resignations.submit("sophia", command("sophia"), "submit");
      resignations.review("amelia", r.id(), review(decision), "review");
      assertThrows(
          ChangeRequest.Conflict.class,
          () -> resignations.withdraw("sophia", r.id(), withdrawal("Too late"), "late"));
      assertTrue(resignations.withdrawal("sophia", r.id()).isEmpty());
    }
    assertEquals(
        1, db.queryForObject("select count(*) from hr_resignation_handoff", Integer.class));
    db.execute("set constraints all immediate");
  }

  @Test
  void sqlRequiresOwnedAtomicWithdrawalAndRetainsImmutableReceipt() {
    var r = resignations.submit("sophia", command("sophia"), "submit");
    db.execute("savepoint incomplete_withdrawal");
    db.update(
        "update hr_resignation set status='WITHDRAWN',version=1 where tenant='atlas' and id=?",
        r.id());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.execute("set constraints all immediate"));
    db.execute("rollback to savepoint incomplete_withdrawal");
    db.execute("savepoint forged_withdrawal");
    db.update(
        "update hr_resignation set status='WITHDRAWN',version=1 where tenant='atlas' and id=?",
        r.id());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "insert into hr_resignation_withdrawal(tenant,request_id,employee_id,actor,reason,input_version,new_version,recorded_at,correlation_id,idempotency_key) values('atlas',?,'E003','amelia','Forged',0,1,clock_timestamp(),'forged','forged-withdrawal')",
                r.id()));
    db.execute("rollback to savepoint forged_withdrawal");
    resignations.withdraw("sophia", r.id(), withdrawal("Real reason"), "real");
    db.execute("set constraints all immediate");
    for (var sql :
        List.of(
            "update hr_resignation_withdrawal set reason='Changed'",
            "delete from hr_resignation_withdrawal",
            "update hr_resignation set status='SUBMITTED',version=0")) {
      db.execute("savepoint immutable_withdrawal");
      assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update(sql));
      db.execute("rollback to savepoint immutable_withdrawal");
    }
    assertEquals("Real reason", resignations.withdrawal("sophia", r.id()).orElseThrow().reason());
  }

  @Test
  void caseEpisodeIsAtomicScopedAndRetainedAcrossDecisionAndReplay() {
    var c = command("sophia");
    var r = resignations.submit("sophia", c, "episode-submit");
    var link = resignations.episode("sophia", r.id());
    assertEquals(1, link.episodeNumber());
    assertEquals("E003", link.employeeId());
    assertEquals(
        people.profile(people.actor("sophia"), "E003").startDate(), link.episodeStartDate());
    assertEquals("COMMAND", link.origin());
    assertEquals("sophia", link.linkedBy());
    assertEquals("episode-submit", link.correlationId());
    assertEquals(link.sourceRecordedAt(), link.linkedAt());
    assertEquals(
        resignations.list("sophia", new RequestQuery("ALL", 0, 12)).items().getFirst().recordedAt(),
        link.sourceRecordedAt());
    assertEquals(link, resignations.episode("amelia", r.id()));
    for (var who : List.of("marcus", "outsider"))
      assertThrows(ChangeRequest.Denied.class, () -> resignations.episode(who, r.id()));
    resignations.submit("sophia", c, "replay");
    resignations.withdraw("sophia", r.id(), withdrawal("Keep original ownership"), "withdraw");
    assertEquals(link, resignations.episode("sophia", r.id()));
    assertEquals(1, db.queryForObject("select count(*) from hr_case_episode", Integer.class));
    db.execute("set constraints all immediate");
  }

  String unlinkedCase(String original, boolean submittedAudit) {
    var id = UUID.randomUUID().toString();
    db.update(
        "insert into hr_resignation(tenant,id,employee_id,last_working_date,reason,status,requested_by,employee_version,recorded_at,version,idempotency_key) select tenant,?,employee_id,last_working_date,'Unlinked synthetic case','SUBMITTED',requested_by,employee_version,clock_timestamp(),0,? from hr_resignation where tenant='atlas' and id=?",
        id,
        UUID.randomUUID().toString(),
        original);
    if (submittedAudit)
      db.update(
          "insert into hr_resignation_audit(tenant,request_id,old_status,new_status,actor,reason,effective_date,recorded_at,correlation_id) select tenant,id,null,'SUBMITTED',requested_by,reason,last_working_date,recorded_at,'unlinked-source' from hr_resignation where tenant='atlas' and id=?",
          id);
    return id;
  }

  @Test
  void sqlRejectsIncompleteForgedAndMutableEpisodeOwnership() {
    var r = resignations.submit("sophia", command("sophia"), "source");
    resignations.withdraw("sophia", r.id(), withdrawal("Release original slot"), "withdraw");
    db.execute("savepoint unlinked_case");
    var id = unlinkedCase(r.id(), false);
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.execute("set constraints all immediate"));
    db.execute("rollback to savepoint unlinked_case");
    db.execute("savepoint forged_case");
    id = unlinkedCase(r.id(), true);
    for (var field :
        List.of(
            "'E001',1,e.start_date,'COMMAND',r.requested_by,'unlinked-source'",
            "r.employee_id,1,e.start_date,'MIGRATED_V42',r.requested_by,'unlinked-source'",
            "r.employee_id,1,e.start_date,'COMMAND','amelia','unlinked-source'",
            "r.employee_id,1,e.start_date,'COMMAND',r.requested_by,'wrong-correlation'")) {
      db.execute("savepoint forged_link");
      String caseId = id;
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              db.update(
                  "insert into hr_case_episode(tenant,request_id,employee_id,episode_number,episode_start_date,origin,linked_by,correlation_id,source_recorded_at,linked_at) select r.tenant,r.id,"
                      + field
                      + ",r.recorded_at,r.recorded_at from hr_resignation r join hr_employee e on e.tenant=r.tenant and e.id=r.employee_id where r.tenant='atlas' and r.id=?",
                  caseId));
      db.execute("rollback to savepoint forged_link");
    }
    db.execute("rollback to savepoint forged_case");
    for (var mutation :
        List.of(
            "update hr_case_episode set episode_start_date=episode_start_date+1",
            "delete from hr_case_episode")) {
      db.execute("savepoint immutable_episode");
      assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update(mutation));
      db.execute("rollback to savepoint immutable_episode");
    }
    assertEquals(1, db.queryForObject("select count(*) from hr_case_episode", Integer.class));
    db.execute("set constraints all immediate");
  }

  @Test
  void migrationRefusesAmbiguousLegacyEpisodeChronology() throws Exception {
    var name = "atlas_episode_probe_" + UUID.randomUUID().toString().replace("-", "");
    db.execute("savepoint episode_preflight");
    db.execute("create schema " + name);
    db.execute("set local search_path to " + name + ",public");
    db.execute(
        "create table hr_resignation(tenant varchar,employee_id varchar,recorded_at timestamptz)");
    db.execute(
        "create table hr_employment_episode(tenant varchar,employee_id varchar,start_date date,recorded_at timestamptz)");
    db.execute("insert into hr_resignation values('synthetic','employee',clock_timestamp())");
    db.execute(
        "insert into hr_employment_episode values('synthetic','employee',current_date-2,clock_timestamp()-interval '1 day')");
    String migration;
    try (var stream =
        getClass().getResourceAsStream("/db/migration/V43__original_case_episode_ownership.sql")) {
      migration =
          new String(
              java.util.Objects.requireNonNull(stream).readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
    }
    var failure =
        assertThrows(
            org.springframework.dao.DataAccessException.class, () -> db.execute(migration));
    assertTrue(
        failure.getMostSpecificCause().getMessage().contains("Ambiguous legacy case episode"));
    db.execute("rollback to savepoint episode_preflight");
  }

  @Test
  void completeOlderWriterDerivesOneImmutableCompatibleLinkAtCommit() {
    var original = resignations.submit("sophia", command("sophia"), "original");
    resignations.withdraw("sophia", original.id(), withdrawal("Release slot"), "withdraw");
    var legacy = unlinkedCase(original.id(), true);
    assertEquals(
        0,
        db.queryForObject(
            "select count(*) from hr_case_episode where request_id=?", Integer.class, legacy));
    db.execute("set constraints all immediate");
    var link = resignations.episode("sophia", legacy);
    assertEquals("COMPAT_COMMAND", link.origin());
    assertEquals(1, link.episodeNumber());
    assertEquals("sophia", link.linkedBy());
    assertEquals("unlinked-source", link.correlationId());
    assertEquals(link.sourceRecordedAt(), link.linkedAt());
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from hr_case_episode where request_id=?", Integer.class, legacy));
    db.execute("set constraints all deferred");
    resignations.withdraw(
        "sophia",
        legacy,
        withdrawal("Compatible request remains manageable"),
        "compatible-withdrawal");
    db.execute("set constraints all immediate");
    assertEquals(link, resignations.episode("amelia", legacy));
  }
}
