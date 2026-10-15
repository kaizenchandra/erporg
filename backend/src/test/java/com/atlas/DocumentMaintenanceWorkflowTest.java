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
class DocumentMaintenanceWorkflowTest {
  @Autowired DocumentTemplatesUseCases templates;
  @Autowired DocumentMaintenanceUseCases maintenance;
  @Autowired DocumentsUseCases documents;
  @Autowired JdbcTemplate jdbc;
  @Autowired JdbcClient db;
  @Autowired WorkforceAccess workforce;

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  DocumentTemplate create() {
    return templates.create(
        "amelia",
        new CreateDocumentTemplate(
            "CORRECTION", "Original", "Original text", true, today(), "Synthetic config", key()),
        "original");
  }

  ReviseDocumentTemplate revision(int expected, LocalDate date, boolean retired) {
    return new ReviseDocumentTemplate(
        BigDecimal.valueOf(expected),
        "Revised",
        "Revised text",
        false,
        date,
        retired,
        "Synthetic revision",
        key());
  }

  DocumentView upload(String user, String name) {
    return documents.upload(
        user,
        new UploadDocument(
            name + ".txt",
            "text/plain",
            Base64.getEncoder().encodeToString(("Synthetic " + name).getBytes()),
            "Synthetic document",
            key()),
        "upload");
  }

  DocumentEvidence initial(DocumentView d) {
    return templates.link(
        "amelia",
        "CORRECTION",
        new LinkDocumentEvidence(d.id(), BigDecimal.ONE, "Synthetic link", key()),
        "initial");
  }

  ReclassifyDocument correction(DocumentView d, int expected) {
    return new ReclassifyDocument(
        d.employeeId(),
        d.id(),
        BigDecimal.valueOf(d.version()),
        BigDecimal.valueOf(expected),
        "Synthetic HR correction",
        key());
  }

  UnitOfWork tx() {
    return new UnitOfWork() {
      public <T> T run(Supplier<T> op) {
        return op.get();
      }
    };
  }

  DocumentTemplatesService at(LocalDate date) {
    return new DocumentTemplatesService(
        new JdbcDocumentTemplateStore(db),
        new JdbcDocumentStore(db),
        new CoreHrDocumentIdentity(workforce),
        tx(),
        Clock.fixed(date.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
  }

  @Test
  void effectiveRevisionAndTerminalRetirementPreserveOriginal() {
    var base = create();
    var future = today().plusDays(1);
    var second = maintenance.revise("amelia", base.code(), revision(1, future, false), "revision");
    assertEquals(2, second.version());
    assertEquals(base, templates.templates("sophia").getFirst());
    assertEquals(second, at(future).templates("sophia").getFirst());
    var third =
        maintenance.revise(
            "amelia", base.code(), revision(2, future.plusDays(1), true), "retirement");
    assertEquals(
        "RETIRED", at(future.plusDays(1)).completeness("sophia", null).items().getFirst().state());
    assertEquals(List.of(third, second, base), maintenance.revisions("sophia", base.code()));
    assertThrows(
        CommandConflict.class,
        () ->
            maintenance.revise(
                "amelia", base.code(), revision(3, future.plusDays(2), false), "reactivate"));
  }

  @Test
  void revisionReplayAndCrossEndpointKeysRemainBound() {
    create();
    var c = revision(1, today(), false);
    var saved = maintenance.revise("amelia", "CORRECTION", c, "revision");
    assertEquals(saved, maintenance.revise("amelia", "CORRECTION", c, "replay"));
    assertThrows(
        CommandConflict.class,
        () ->
            maintenance.revise(
                "amelia",
                "CORRECTION",
                new ReviseDocumentTemplate(
                    c.expectedVersion(),
                    "Changed",
                    c.body(),
                    c.required(),
                    c.effectiveDate(),
                    c.retired(),
                    c.reason(),
                    c.idempotencyKey()),
                "conflict"));
    assertThrows(
        CommandConflict.class,
        () ->
            templates.create(
                "amelia",
                new CreateDocumentTemplate(
                    "NEWCODE", "Title", "Body", true, today(), "Reason", c.idempotencyKey()),
                "reuse"));
  }

  @Test
  void revisionDeniesRolesTenantStaleFractionalAndPastDate() {
    create();
    for (var user : List.of("sophia", "marcus", "outsider"))
      assertThrows(
          AccessDenied.class,
          () -> maintenance.revise(user, "CORRECTION", revision(1, today(), false), "denied"));
    assertThrows(AccessDenied.class, () -> maintenance.revisions("outsider", "CORRECTION"));
    for (var v : Arrays.asList(null, new BigDecimal("1.5")))
      assertThrows(
          InputValidation.class,
          () ->
              maintenance.revise(
                  "amelia",
                  "CORRECTION",
                  new ReviseDocumentTemplate(
                      v, "Title", "Body", true, today(), false, "Reason", key()),
                  "invalid"));
    assertThrows(
        InputValidation.class,
        () ->
            maintenance.revise(
                "amelia", "CORRECTION", revision(1, today().minusDays(1), false), "past"));
    maintenance.revise("amelia", "CORRECTION", revision(1, today(), false), "valid");
    assertThrows(
        CommandConflict.class,
        () -> maintenance.revise("amelia", "CORRECTION", revision(1, today(), false), "stale"));
  }

  @Test
  void correctionProjectsReplacementAndRetainsHistoryAndReservation() {
    create();
    var old = upload("sophia", "Original");
    var first = initial(old);
    var fresh = upload("sophia", "Corrected");
    var c = correction(fresh, 1);
    var second = maintenance.reclassify("amelia", "CORRECTION", c, "correct");
    assertEquals(2, second.classificationVersion());
    assertEquals(first.rootId(), second.previousRootId());
    assertEquals(second, maintenance.reclassify("amelia", "CORRECTION", c, "replay"));
    assertEquals(fresh.id(), templates.completeness("sophia", null).items().getFirst().rootId());
    assertEquals(List.of(second, first), maintenance.classifications("sophia", "CORRECTION", null));
    assertThrows(
        CommandConflict.class,
        () -> maintenance.reclassify("amelia", "CORRECTION", correction(old, 2), "reuse"));
    assertEquals("AVAILABLE", documents.versions("sophia", old.id()).getFirst().status());
    assertDoesNotThrow(() -> jdbc.execute("set constraints all immediate"));
  }

  @Test
  void correctionChecksRolesOwnerVersionAndRetiredState() {
    create();
    var first = upload("sophia", "First");
    initial(first);
    var fresh = upload("sophia", "Fresh");
    for (var user : List.of("sophia", "marcus", "outsider"))
      assertThrows(
          AccessDenied.class,
          () -> maintenance.reclassify(user, "CORRECTION", correction(fresh, 1), "denied"));
    var foreign = upload("amelia", "Foreign");
    assertThrows(
        AccessDenied.class,
        () ->
            maintenance.reclassify(
                "amelia",
                "CORRECTION",
                new ReclassifyDocument(
                    "E003", foreign.id(), BigDecimal.ONE, BigDecimal.ONE, "Wrong employee", key()),
                "denied"));
    assertThrows(
        AccessDenied.class, () -> maintenance.classifications("marcus", "CORRECTION", "E003"));
    maintenance.revise("amelia", "CORRECTION", revision(1, today(), true), "retire");
    assertThrows(
        CommandConflict.class,
        () -> maintenance.reclassify("amelia", "CORRECTION", correction(fresh, 1), "retired"));
  }

  @Test
  void staleCorrectionAndOriginalCommandReplayRetainOldSnapshot() {
    create();
    var old = upload("sophia", "First");
    var link = new LinkDocumentEvidence(old.id(), BigDecimal.ONE, "Original link", key());
    var first = templates.link("amelia", "CORRECTION", link, "initial");
    var fresh = upload("sophia", "Fresh");
    maintenance.reclassify("amelia", "CORRECTION", correction(fresh, 1), "correct");
    assertEquals(first, templates.link("amelia", "CORRECTION", link, "old replay"));
    var third = upload("sophia", "Third");
    assertThrows(
        CommandConflict.class,
        () -> maintenance.reclassify("amelia", "CORRECTION", correction(third, 1), "stale"));
    assertThrows(
        InputValidation.class,
        () ->
            maintenance.reclassify(
                "amelia",
                "CORRECTION",
                new ReclassifyDocument(
                    "E003", third.id(), BigDecimal.ONE, null, "Missing version", key()),
                "invalid"));
  }

  @Test
  void databaseRejectsRevisionGap() {
    create();
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "insert into document_template_revision values('atlas','CORRECTION',3,'Title','Body',true,current_date,false,'amelia','Reason',now(),'gap','gap-key-001')"));
  }

  @Test
  void databaseRejectsRevisionMutation() {
    create();
    maintenance.revise("amelia", "CORRECTION", revision(1, today(), false), "revision");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("update document_template_revision set retired=true"));
  }

  @Test
  void databaseRejectsCorrectionMutation() {
    create();
    initial(upload("sophia", "First"));
    maintenance.reclassify(
        "amelia", "CORRECTION", correction(upload("sophia", "Fresh"), 1), "correct");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("delete from document_template_reclassification"));
  }

  @Test
  void databaseRejectsCorrectionWrongOwner() {
    create();
    var first = upload("sophia", "First");
    initial(first);
    var wrong = upload("amelia", "Foreign");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "insert into document_template_reclassification values(?,?,?,?,?,?,?,?,?,?,?,?)",
                "atlas",
                "CORRECTION",
                "E003",
                2,
                first.id(),
                wrong.id(),
                1,
                "amelia",
                "Wrong owner",
                java.sql.Timestamp.from(Instant.now()),
                "bad-owner",
                key()));
  }

  @Test
  void correctionRejectsBlockedLatestAndHistoricalFile() {
    create();
    initial(upload("sophia", "First"));
    var fresh = upload("sophia", "Fresh");
    String malware = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    var blocked =
        documents.version(
            "sophia",
            fresh.id(),
            new VersionDocument(
                1,
                new UploadDocument(
                    "Blocked.txt",
                    "text/plain",
                    Base64.getEncoder().encodeToString(malware.getBytes()),
                    "Synthetic malware",
                    key())),
            "blocked");
    assertEquals("REJECTED", blocked.status());
    assertThrows(
        CommandConflict.class,
        () -> maintenance.reclassify("amelia", "CORRECTION", correction(fresh, 1), "historical"));
    assertThrows(
        CommandConflict.class,
        () -> maintenance.reclassify("amelia", "CORRECTION", correction(blocked, 1), "malware"));
  }

  @Test
  void databaseRejectsCorrectionGap() {
    create();
    var first = upload("sophia", "First");
    initial(first);
    var fresh = upload("sophia", "Fresh");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "insert into document_template_reclassification values(?,?,?,?,?,?,?,?,?,?,?,?)",
                "atlas",
                "CORRECTION",
                "E003",
                3,
                first.id(),
                fresh.id(),
                1,
                "amelia",
                "Gap",
                java.sql.Timestamp.from(Instant.now()),
                "bad-gap",
                key()));
  }
}
