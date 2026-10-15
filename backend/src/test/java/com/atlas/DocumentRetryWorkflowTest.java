package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.documents.adapter.out.integration.CoreHrDocumentIdentity;
import com.atlas.modules.documents.adapter.out.persistence.JdbcDocumentStore;
import com.atlas.modules.documents.adapter.out.scanning.ClamAvScanner;
import com.atlas.modules.documents.adapter.out.storage.LocalPrivateObjects;
import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.in.DocumentsUseCases;
import com.atlas.modules.documents.application.port.out.UnitOfWork;
import com.atlas.modules.documents.application.usecase.DocumentsService;
import com.atlas.shared.domain.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.*;
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
class DocumentRetryWorkflowTest {
  @Autowired DocumentsUseCases documents;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient client;
  @Autowired com.atlas.modules.corehr.api.WorkforceAccess workforce;

  @Value("${atlas.documents.local-root:/private/tmp/atlas-private-documents}")
  String root;

  DocumentsService outage() {
    return new DocumentsService(
        new JdbcDocumentStore(client),
        new CoreHrDocumentIdentity(workforce),
        new LocalPrivateObjects(Path.of(root)),
        new ClamAvScanner("127.0.0.1", 1),
        new UnitOfWork() {
          public <T> T run(Supplier<T> op) {
            return op.get();
          }
        },
        Clock.systemUTC());
  }

  DocumentView quarantine(String content) {
    return outage()
        .upload(
            "sophia",
            new UploadDocument(
                "Retry.txt",
                "text/plain",
                Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)),
                "Synthetic retry fixture",
                UUID.randomUUID().toString()),
            "quarantine");
  }

  RetryDocumentScan command(int version) {
    return new RetryDocumentScan(
        version, "Scanner restored; inspect result", UUID.randomUUID().toString());
  }

  @Test
  void cleanRetryPreservesOriginalAndEnablesDownload() {
    var d = quarantine("Retry clean content");
    var c = command(0);
    var result = documents.retry("amelia", d.id(), c, "retry-correlation");
    assertEquals("AVAILABLE", result.status());
    assertEquals(1, result.scanAttempts());
    assertEquals(result, documents.retry("amelia", d.id(), c, "replay"));
    assertEquals(
        "QUARANTINED",
        jdbc.queryForObject(
            "select status from employee_document where id=?", String.class, d.id()));
    assertEquals(
        1, jdbc.queryForObject("select count(*) from employee_document_scan_retry", Integer.class));
    assertEquals(
        "Retry clean content",
        new String(
            documents.download("sophia", d.id(), "download").content(), StandardCharsets.UTF_8));
    assertTrue(
        documents.history("sophia", d.id()).stream()
            .anyMatch(
                a ->
                    a.action().equals("RESCAN_AVAILABLE")
                        && a.correlationId().equals("retry-correlation")));
    assertThrows(
        CommandConflict.class, () -> documents.retry("amelia", d.id(), command(1), "terminal"));
  }

  @Test
  void eicarRetryRejectsAndCannotBypass() {
    var d = quarantine("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*");
    assertEquals("REJECTED", documents.retry("amelia", d.id(), command(0), "retry").status());
    assertThrows(CommandConflict.class, () -> documents.download("sophia", d.id(), "blocked"));
    assertThrows(
        CommandConflict.class, () -> documents.retry("amelia", d.id(), command(1), "again"));
  }

  @Test
  void threeOutageAttemptsAreBoundedAndReplaysRemainStable() {
    var d = quarantine("Bounded fixture");
    var first = command(0);
    var result = outage().retry("amelia", d.id(), first, "one");
    for (int i = 1; i < 3; i++)
      assertEquals(i + 1, outage().retry("amelia", d.id(), command(i), "outage").scanAttempts());
    assertEquals(result, documents.retry("amelia", d.id(), first, "old-replay"));
    assertThrows(
        CommandConflict.class, () -> documents.retry("amelia", d.id(), command(3), "limit"));
    assertThrows(CommandConflict.class, () -> documents.download("sophia", d.id(), "blocked"));
  }

  @Test
  void permissionAndVersionValidation() {
    var d = quarantine("Scope fixture");
    for (var user : List.of("sophia", "marcus", "outsider"))
      assertThrows(AccessDenied.class, () -> documents.retry(user, d.id(), command(0), "denied"));
    assertThrows(
        InputValidation.class,
        () ->
            documents.retry(
                "amelia",
                d.id(),
                new RetryDocumentScan(null, "Missing version", UUID.randomUUID().toString()),
                "missing"));
    assertThrows(
        InputValidation.class,
        () ->
            documents.retry(
                "amelia",
                d.id(),
                new RetryDocumentScan(
                    new java.math.BigDecimal("0.5"),
                    "Fractional version",
                    UUID.randomUUID().toString()),
                "fraction"));
    assertThrows(
        CommandConflict.class, () -> documents.retry("amelia", d.id(), command(1), "stale"));
  }

  @Test
  void conflictingReplayCannotChangeReasonOrActor() {
    var d = quarantine("Conflict fixture");
    var c = command(0);
    documents.retry("amelia", d.id(), c, "commit");
    assertThrows(
        CommandConflict.class,
        () ->
            documents.retry(
                "amelia",
                d.id(),
                new RetryDocumentScan(0, "Different reason", c.idempotencyKey()),
                "changed"));
    assertThrows(CommandConflict.class, () -> documents.retry("olivia", d.id(), c, "other"));
  }

  @Test
  void databaseMakesRetryLedgerImmutable() {
    var d = quarantine("Immutable retry fixture");
    documents.retry("amelia", d.id(), command(0), "retry");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "update employee_document_scan_retry set reason='Changed' where document_id=?",
                d.id()));
  }

  @org.junit.jupiter.api.io.TempDir Path temp;

  @Test
  void corruptedContentIsRejectedBeforeScanner() throws Exception {
    var objects = new LocalPrivateObjects(temp.toRealPath());
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> operation) {
            return operation.get();
          }
        };
    var initial =
        new DocumentsService(
            new JdbcDocumentStore(client),
            new CoreHrDocumentIdentity(workforce),
            objects,
            new ClamAvScanner("127.0.0.1", 1),
            tx,
            Clock.systemUTC());
    var d =
        initial.upload(
            "sophia",
            new UploadDocument(
                "Corruption.txt",
                "text/plain",
                Base64.getEncoder()
                    .encodeToString("Synthetic integrity retry".getBytes(StandardCharsets.UTF_8)),
                "Integrity fixture",
                UUID.randomUUID().toString()),
            "initial");
    var key =
        jdbc.queryForObject(
            "select object_key from employee_document where id=?", String.class, d.id());
    java.nio.file.Files.writeString(temp.resolve(key), "Corrupted bytes");
    var guarded =
        new DocumentsService(
            new JdbcDocumentStore(client),
            new CoreHrDocumentIdentity(workforce),
            objects,
            bytes -> {
              throw new AssertionError("Scanner must not receive corrupt content");
            },
            tx,
            Clock.systemUTC());
    assertThrows(
        CommandConflict.class, () -> guarded.retry("amelia", d.id(), command(0), "corrupt"));
    assertEquals(
        0, jdbc.queryForObject("select count(*) from employee_document_scan_retry", Integer.class));
  }

  @Test
  void roleRevocationDuringExternalScanPreventsCommit() {
    var d = quarantine("Revoked retry fixture");
    var service =
        new DocumentsService(
            new JdbcDocumentStore(client),
            new CoreHrDocumentIdentity(workforce),
            new LocalPrivateObjects(Path.of(root)),
            bytes -> {
              var result = new ClamAvScanner("127.0.0.1", 3311).scan(bytes);
              jdbc.update("update identity_membership set role='EMPLOYEE' where subject='amelia'");
              return result;
            },
            new UnitOfWork() {
              public <T> T run(Supplier<T> operation) {
                return operation.get();
              }
            },
            Clock.systemUTC());
    assertThrows(AccessDenied.class, () -> service.retry("amelia", d.id(), command(0), "revoked"));
    assertEquals(
        0, jdbc.queryForObject("select count(*) from employee_document_scan_retry", Integer.class));
  }
}
