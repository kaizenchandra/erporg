package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.in.DocumentsUseCases;
import com.atlas.shared.domain.*;
import java.nio.charset.StandardCharsets;
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
class DocumentVersionWorkflowTest {
  @Autowired DocumentsUseCases documents;
  @Autowired JdbcTemplate jdbc;

  UploadDocument input(String filename, String text) {
    return new UploadDocument(
        filename,
        "text/plain",
        Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)),
        "Synthetic document revision",
        UUID.randomUUID().toString());
  }

  @Test
  void appendPreservesHistoricalBytesAndLatestSearch() {
    var old = documents.upload("sophia", input("Original.txt", "Original bytes"), "initial");
    var command = new VersionDocument(1, input("Updated.txt", "Updated bytes"));
    var current = documents.version("amelia", old.id(), command, "version");
    assertEquals(old.rootId(), current.rootId());
    assertEquals(2, current.version());
    assertEquals("E003", current.employeeId());
    assertEquals(current, documents.version("amelia", old.id(), command, "replay"));
    assertEquals(
        List.of(current.id(), old.id()),
        documents.versions("sophia", current.id()).stream().map(DocumentView::id).toList());
    assertEquals(1, documents.list("sophia", "", 0, 8).total());
    assertEquals(0, documents.list("sophia", "Original", 0, 8).total());
    assertEquals(
        "Original bytes",
        new String(
            documents.download("sophia", old.id(), "historical").content(),
            StandardCharsets.UTF_8));
    assertEquals(
        "Updated bytes",
        new String(
            documents.download("sophia", current.id(), "current").content(),
            StandardCharsets.UTF_8));
    assertTrue(
        documents.history("sophia", current.id()).stream()
            .anyMatch(
                h -> h.action().equals("VERSION_UPLOADED") && h.correlationId().equals("version")));
    assertDoesNotThrow(() -> jdbc.execute("set constraints all immediate"));
  }

  @Test
  void selfCanAppendButTeamManagerOtherEmployeeAndTenantCannot() {
    var old = documents.upload("sophia", input("Own.txt", "Self version bytes"), "initial");
    for (var user : List.of("marcus", "outsider")) {
      assertThrows(
          AccessDenied.class,
          () ->
              documents.version(
                  user,
                  old.id(),
                  new VersionDocument(1, input("Private.txt", "Private")),
                  "denied"));
      assertThrows(AccessDenied.class, () -> documents.versions(user, old.id()));
    }
    var current =
        documents.version(
            "sophia",
            old.id(),
            new VersionDocument(1, input("Own.txt", "Self updated bytes")),
            "self");
    assertEquals(2, current.version());
  }

  @Test
  void staleHistoricalParentAndConflictingReplayRejected() {
    var old = documents.upload("sophia", input("Stale.txt", "Stale original"), "initial");
    var c = new VersionDocument(1, input("Stale.txt", "Stale replacement"));
    var v2 = documents.version("sophia", old.id(), c, "replace");
    assertThrows(
        CommandConflict.class,
        () ->
            documents.version(
                "sophia",
                old.id(),
                new VersionDocument(1, input("Stale.txt", "Another")),
                "stale"));
    assertThrows(
        CommandConflict.class,
        () ->
            documents.version(
                "sophia",
                old.id(),
                new VersionDocument(2, input("Stale.txt", "Branch")),
                "branch"));
    assertThrows(
        CommandConflict.class,
        () ->
            documents.version(
                "sophia",
                old.id(),
                new VersionDocument(
                    1,
                    new UploadDocument(
                        c.upload().filename(),
                        c.upload().mediaType(),
                        c.upload().contentBase64(),
                        "Changed reason",
                        c.upload().idempotencyKey())),
                "changed"));
    assertThrows(
        CommandConflict.class, () -> documents.upload("sophia", c.upload(), "wrong-command"));
    assertEquals(
        3,
        documents
            .version(
                "sophia",
                v2.id(),
                new VersionDocument(2, input("Stale.txt", "Third bytes")),
                "third")
            .version());
  }

  @Test
  void rejectedReplacementIsLatestWithoutAutomaticFallback() {
    var old = documents.upload("sophia", input("Malware.txt", "Clean original"), "initial");
    var rejected =
        documents.version(
            "sophia",
            old.id(),
            new VersionDocument(
                1,
                input(
                    "Malware.txt",
                    "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")),
            "replace");
    assertEquals("REJECTED", rejected.status());
    assertEquals(rejected.id(), documents.list("sophia", "", 0, 8).items().getFirst().id());
    assertThrows(
        CommandConflict.class, () -> documents.download("sophia", rejected.id(), "blocked"));
    assertEquals(
        "Clean original",
        new String(
            documents.download("sophia", old.id(), "explicit-history").content(),
            StandardCharsets.UTF_8));
  }

  @Test
  void missingFractionalVersionAndVersionLedgerMutationRejected() {
    var old = documents.upload("sophia", input("Validation.txt", "Validation bytes"), "initial");
    assertThrows(
        InputValidation.class,
        () ->
            documents.version(
                "sophia",
                old.id(),
                new VersionDocument(null, input("Validation.txt", "New")),
                "missing"));
    assertThrows(
        InputValidation.class,
        () ->
            documents.version(
                "sophia",
                old.id(),
                new VersionDocument(
                    new java.math.BigDecimal("1.5"), input("Validation.txt", "New")),
                "fraction"));
    var current =
        documents.version(
            "sophia",
            old.id(),
            new VersionDocument(1, input("Validation.txt", "Valid new")),
            "valid");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "update employee_document_version set reason='Changed' where document_id=?",
                current.id()));
  }

  @Test
  void databaseRejectsCrossEmployeeVersionLink() {
    var root = documents.upload("sophia", input("Scope root.txt", "Scope root"), "root");
    var other =
        documents.upload("amelia", input("Other owner.txt", "Other employee bytes"), "other");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "insert into employee_document_version(tenant,root_id,parent_id,document_id,version,actor,reason,recorded_at,correlation_id) select tenant,?,?,id,2,requested_by,reason,recorded_at,'cross-owner' from employee_document where id=?",
                root.id(),
                root.id(),
                other.id()));
  }
}
