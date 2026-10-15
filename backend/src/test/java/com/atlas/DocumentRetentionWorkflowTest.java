package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.api.WorkforceAccess;
import com.atlas.modules.documents.adapter.out.integration.CoreHrDocumentIdentity;
import com.atlas.modules.documents.adapter.out.persistence.*;
import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.in.*;
import com.atlas.modules.documents.application.port.out.*;
import com.atlas.modules.documents.application.usecase.*;
import com.atlas.shared.domain.*;
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
class DocumentRetentionWorkflowTest {
  @Autowired DocumentRetentionUseCases retention;
  @Autowired DocumentsUseCases documents;
  @Autowired JdbcTemplate jdbc;
  @Autowired JdbcClient db;
  @Autowired WorkforceAccess workforce;

  String key() {
    return UUID.randomUUID().toString();
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  DocumentView upload() {
    return documents.upload(
        "sophia",
        new UploadDocument(
            "Governed.txt",
            "text/plain",
            Base64.getEncoder().encodeToString("Synthetic private bytes".getBytes()),
            "Synthetic governance fixture",
            key()),
        "upload");
  }

  RetentionCommand policy(int version, LocalDate date) {
    return new RetentionCommand(
        "SET_POLICY",
        BigDecimal.valueOf(version),
        date,
        "Explicit synthetic company basis",
        null,
        null,
        "Synthetic retention reason",
        key());
  }

  RetentionCommand place(int version, String ref) {
    return new RetentionCommand(
        "PLACE_HOLD",
        BigDecimal.valueOf(version),
        null,
        null,
        null,
        ref,
        "Synthetic hold reason",
        key());
  }

  RetentionCommand release(int version, String hold) {
    return new RetentionCommand(
        "RELEASE_HOLD",
        BigDecimal.valueOf(version),
        null,
        null,
        hold,
        null,
        "Synthetic release reason",
        key());
  }

  DocumentRetentionService at(LocalDate date) {
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> op) {
            return op.get();
          }
        };
    return new DocumentRetentionService(
        new JdbcDocumentRetentionStore(db),
        new JdbcDocumentStore(db),
        new CoreHrDocumentIdentity(workforce),
        tx,
        Clock.fixed(date.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
  }

  @Test
  void explicitPolicyAndDateBoundaryNeverEnableDeletion() {
    var d = upload();
    assertEquals("NO_POLICY", retention.review("amelia", d.id()).state());
    var e = retention.command("amelia", d.id(), policy(0, today()), "policy");
    assertEquals("policy", e.correlationId());
    assertEquals("RETAIN", retention.review("amelia", d.id()).state());
    assertEquals("REVIEW_DUE", at(today().plusDays(1)).review("amelia", d.id()).state());
    assertFalse(at(today().plusDays(1)).review("amelia", d.id()).deletionAvailable());
    assertEquals(
        "Synthetic private bytes",
        new String(documents.download("sophia", d.id(), "retained").content()));
  }

  @Test
  void independentMultipleHoldsAndReleasePreserveOtherHoldAndPolicy() {
    var d = upload();
    retention.command("amelia", d.id(), policy(0, today().plusDays(5)), "policy");
    var h1 = retention.command("amelia", d.id(), place(1, "CASE-A"), "hold-a");
    var h2 = retention.command("olivia", d.id(), place(2, "CASE-B"), "hold-b");
    retention.command("olivia", d.id(), release(3, h1.holdId()), "release-a");
    assertEquals("ON_HOLD", retention.review("amelia", d.id()).state());
    retention.command("amelia", d.id(), release(4, h2.holdId()), "release-b");
    var review = retention.review("olivia", d.id());
    assertEquals("RETAIN", review.state());
    assertTrue(review.holds().stream().noneMatch(RetentionReview.Hold::active));
    assertEquals(5, review.history().size());
    assertDoesNotThrow(() -> jdbc.execute("set constraints all immediate"));
  }

  @Test
  void sameEmployeeAliasCannotReleaseAndOrdinaryRolesCannotReadLegalDetails() {
    var d = upload();
    var hold = retention.command("amelia", d.id(), place(0, "PRIVATE-CASE"), "hold");
    jdbc.update(
        "insert into identity_membership values('hr-alias','atlas','E001','Synthetic alias','HR')");
    for (var actor : List.of("amelia", "hr-alias"))
      assertThrows(
          AccessDenied.class,
          () -> retention.command(actor, d.id(), release(1, hold.holdId()), "self"));
    for (var actor : List.of("sophia", "marcus", "outsider")) {
      assertThrows(AccessDenied.class, () -> retention.review(actor, d.id()));
      assertThrows(
          AccessDenied.class, () -> retention.command(actor, d.id(), place(1, "DENIED"), "denied"));
    }
    assertTrue(
        documents.history("sophia", d.id()).stream()
            .noneMatch(e -> e.reason().contains("PRIVATE-CASE")));
  }

  @Test
  void stableReplayAfterLaterEventsAndCrossRootConflicts() {
    var d = upload();
    var c = policy(0, today());
    var first = retention.command("amelia", d.id(), c, "policy");
    retention.command("amelia", d.id(), place(1, "CASE"), "hold");
    assertEquals(first, retention.command("amelia", d.id(), c, "replay"));
    var other = upload();
    assertThrows(
        CommandConflict.class, () -> retention.command("amelia", other.id(), c, "cross-root"));
    assertThrows(
        CommandConflict.class,
        () ->
            retention.command(
                "amelia",
                d.id(),
                new RetentionCommand(
                    c.action(),
                    c.expectedVersion(),
                    c.retainUntil(),
                    "Changed basis",
                    null,
                    null,
                    c.reason(),
                    c.idempotencyKey()),
                "conflict"));
  }

  @Test
  void validatesExactVersionShapeDatesAndNeverShortens() {
    var d = upload();
    for (var v : Arrays.asList(null, new BigDecimal("0.5")))
      assertThrows(
          InputValidation.class,
          () ->
              retention.command(
                  "amelia",
                  d.id(),
                  new RetentionCommand("PLACE_HOLD", v, null, null, null, "CASE", "Reason", key()),
                  "invalid"));
    assertThrows(
        InputValidation.class,
        () -> retention.command("amelia", d.id(), policy(0, today().minusDays(1)), "past"));
    retention.command("amelia", d.id(), policy(0, today().plusDays(10)), "policy");
    assertThrows(
        CommandConflict.class,
        () -> retention.command("amelia", d.id(), policy(1, today().plusDays(1)), "shorten"));
    assertThrows(
        CommandConflict.class,
        () -> retention.command("amelia", d.id(), place(0, "STALE"), "stale"));
    assertThrows(
        InputValidation.class,
        () ->
            retention.command(
                "amelia",
                d.id(),
                new RetentionCommand(
                    "PLACE_HOLD", BigDecimal.ONE, today(), null, null, "CASE", "Reason", key()),
                "shape"));
  }

  @Test
  void duplicateMissingAndReleasedHoldsFailWithoutMutation() {
    var d = upload();
    var h = retention.command("amelia", d.id(), place(0, "CASE"), "hold");
    assertThrows(
        CommandConflict.class,
        () -> retention.command("amelia", d.id(), place(1, "CASE"), "duplicate"));
    assertThrows(
        CommandConflict.class,
        () -> retention.command("olivia", d.id(), release(1, key()), "missing"));
    retention.command("olivia", d.id(), release(1, h.holdId()), "release");
    assertThrows(
        CommandConflict.class,
        () -> retention.command("olivia", d.id(), release(2, h.holdId()), "released"));
    assertEquals(2, retention.review("amelia", d.id()).version());
  }

  @Test
  void governanceFollowsFutureFileVersionsAndByteAuditRemains() {
    var d = upload();
    var h = retention.command("amelia", d.id(), place(0, "CHAIN"), "hold");
    var next =
        documents.version(
            "sophia",
            d.id(),
            new VersionDocument(
                1,
                new UploadDocument(
                    "Next.txt",
                    "text/plain",
                    Base64.getEncoder().encodeToString("Replacement bytes".getBytes()),
                    "Synthetic version",
                    key())),
            "version");
    assertEquals(retention.review("amelia", d.id()), retention.review("amelia", next.id()));
    assertEquals(h, retention.review("amelia", next.id()).history().getFirst());
    assertEquals(
        "Synthetic private bytes",
        new String(documents.download("sophia", d.id(), "old").content()));
  }

  @Test
  void databaseRejectsSequenceGap() {
    var d = upload();
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "insert into document_retention_event values('atlas',?,'E003',2,'SET_POLICY',current_date,'Basis',null,null,'amelia','E001','Reason',now(),'gap','gap-key-001')",
                d.id()));
  }

  @Test
  void databaseRejectsSelfRelease() {
    var d = upload();
    var h = retention.command("amelia", d.id(), place(0, "CASE"), "hold");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "insert into document_retention_event values('atlas',?,'E003',2,'RELEASE_HOLD',null,null,?,null,'hr-alias','E001','Reason',now(),'self','self-key-001')",
                d.id(),
                h.holdId()));
  }

  @Test
  void databaseRejectsShortenedPolicy() {
    var d = upload();
    retention.command("amelia", d.id(), policy(0, today().plusDays(10)), "policy");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "insert into document_retention_event values('atlas',?,'E003',2,'SET_POLICY',current_date,'Basis',null,null,'amelia','E001','Reason',now(),'short','short-key-001')",
                d.id()));
  }

  @Test
  void databaseRejectsJournalMutation() {
    var d = upload();
    retention.command("amelia", d.id(), place(0, "CASE"), "hold");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("delete from document_retention_event where root_id=?", d.id()));
  }

  @Test
  void eventLimitRejectsWithoutBypass() {
    var d = upload();
    for (int n = 1; n <= 100; n++)
      jdbc.update(
          "insert into document_retention_event values('atlas',?,'E003',?,'SET_POLICY',current_date,'Basis',null,null,'amelia','E001','Reason',now(),'fixture',?)",
          d.id(),
          n,
          "fixture-key-" + n);
    assertEquals(100, retention.review("amelia", d.id()).version());
    assertThrows(
        CommandConflict.class,
        () -> retention.command("amelia", d.id(), place(100, "LIMIT"), "limit"));
  }

  @Test
  void originalSubjectCannotReleaseAfterEmployeeRebinding() {
    var d = upload();
    var h = retention.command("amelia", d.id(), place(0, "REBIND"), "hold");
    jdbc.update("update identity_membership set employee_id='E005' where subject='amelia'");
    assertThrows(
        AccessDenied.class,
        () -> retention.command("amelia", d.id(), release(1, h.holdId()), "rebound"));
  }

  @Test
  void databaseRejectsSameSubjectWithChangedEmployeeIdentity() {
    var d = upload();
    var h = retention.command("amelia", d.id(), place(0, "SUBJECT"), "hold");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "insert into document_retention_event values('atlas',?,'E003',2,'RELEASE_HOLD',null,null,?,null,'amelia','E005','Reason',now(),'subject','subject-key-001')",
                d.id(),
                h.holdId()));
  }
}
