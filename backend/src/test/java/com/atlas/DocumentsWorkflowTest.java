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
import com.atlas.modules.documents.domain.model.DocumentPolicy;
import com.atlas.shared.domain.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
class DocumentsWorkflowTest {
  @Autowired DocumentsUseCases documents;
  @Autowired JdbcTemplate jdbc;
  @Autowired JdbcClient client;
  @Autowired com.atlas.modules.corehr.api.WorkforceAccess workforce;
  @TempDir Path temp;

  UploadDocument input(String text) {
    return new UploadDocument(
        "Synthetic letter.txt",
        "text/plain",
        Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)),
        "Synthetic HR document",
        UUID.randomUUID().toString());
  }

  @Test
  void realScannerCleanDownloadReplayAudit() {
    var c = input("Synthetic employment letter");
    var d = documents.upload("sophia", c, "upload");
    assertEquals("AVAILABLE", d.status());
    assertTrue(d.scanner().startsWith("ClamAV "));
    assertEquals(d, documents.upload("sophia", c, "replay"));
    assertEquals(
        "Synthetic employment letter",
        new String(
            documents.download("sophia", d.id(), "download").content(), StandardCharsets.UTF_8));
    assertEquals(3, documents.history("amelia", d.id()).size());
    assertDoesNotThrow(() -> jdbc.execute("set constraints all immediate"));
    assertThrows(
        CommandConflict.class,
        () ->
            documents.upload(
                "sophia",
                new UploadDocument(
                    c.filename(),
                    c.mediaType(),
                    Base64.getEncoder().encodeToString("Changed".getBytes(StandardCharsets.UTF_8)),
                    c.reason(),
                    c.idempotencyKey()),
                "changed"));
  }

  @Test
  void realScannerEicarRejection() {
    var d =
        documents.upload(
            "sophia",
            input("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"),
            "eicar");
    assertEquals("REJECTED", d.status());
    assertThrows(CommandConflict.class, () -> documents.download("sophia", d.id(), "blocked"));
  }

  @Test
  void scannerOutageQuarantinesWithoutDownload() throws Exception {
    var service =
        new DocumentsService(
            new JdbcDocumentStore(client),
            new CoreHrDocumentIdentity(workforce),
            new LocalPrivateObjects(temp.toRealPath()),
            new ClamAvScanner("127.0.0.1", 1),
            new UnitOfWork() {
              public <T> T run(Supplier<T> op) {
                return op.get();
              }
            },
            Clock.systemUTC());
    var d = service.upload("sophia", input("Quarantine fixture"), "outage");
    assertEquals("QUARANTINED", d.status());
    assertThrows(CommandConflict.class, () -> service.download("sophia", d.id(), "blocked"));
  }

  @Test
  void privateScopeAndLiteralSearch() {
    var d = documents.upload("sophia", input("Private scope fixture"), "scope");
    for (var user : List.of("marcus", "outsider", "olivia")) {
      if (!user.equals("olivia")) {
        assertEquals(0, documents.list(user, "", 0, 8).total());
        assertThrows(AccessDenied.class, () -> documents.download(user, d.id(), "denied"));
        assertThrows(AccessDenied.class, () -> documents.history(user, d.id()));
      }
    }
    assertEquals(1, documents.list("amelia", "Synthetic", 0, 8).total());
    assertEquals(0, documents.list("amelia", "%", 0, 8).total());
  }

  @Test
  void uploadValidationRejectsSpoofedBinaryPathsAndOversizedContent() {
    assertThrows(
        InputValidation.class,
        () -> DocumentPolicy.validate("../secret.txt", "text/plain", "content".getBytes()));
    assertThrows(
        InputValidation.class,
        () -> DocumentPolicy.validate("safe.txt", "application/pdf", "content".getBytes()));
    assertThrows(
        InputValidation.class,
        () -> DocumentPolicy.validate("safe.txt", "text/plain", new byte[16385]));
    assertThrows(
        InputValidation.class,
        () -> DocumentPolicy.validate("safe.txt", "text/plain", new byte[] {(byte) 0xff}));
    assertDoesNotThrow(
        () ->
            DocumentPolicy.validate(
                "Résumé.txt", "text/plain", "Résumé".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void localObjectsPrivatePermissionsAndIntegrityGuard() throws Exception {
    var objects = new LocalPrivateObjects(temp.toRealPath());
    var service =
        new DocumentsService(
            new JdbcDocumentStore(client),
            new CoreHrDocumentIdentity(workforce),
            objects,
            new ClamAvScanner("127.0.0.1", 3311),
            new UnitOfWork() {
              public <T> T run(Supplier<T> op) {
                return op.get();
              }
            },
            Clock.systemUTC());
    var d = service.upload("sophia", input("Integrity fixture"), "integrity");
    var key =
        jdbc.queryForObject(
            "select object_key from employee_document where id=?", String.class, d.id());
    assertEquals(
        "rw-------",
        java.nio.file.attribute.PosixFilePermissions.toString(
            Files.getPosixFilePermissions(temp.resolve(key))));
    assertEquals(
        "rwx------",
        java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(temp)));
    Files.writeString(temp.resolve(key), "Corrupted synthetic fixture");
    assertThrows(CommandConflict.class, () -> service.download("sophia", d.id(), "blocked"));
  }

  @Test
  void storageRejectsSymlinkAndTraversal() throws Exception {
    var objects = new LocalPrivateObjects(temp.toRealPath());
    assertThrows(CommandConflict.class, () -> objects.read("../outside"));
    var directory = temp.resolve("a".repeat(64));
    Files.createSymbolicLink(directory, temp.getParent());
    assertThrows(
        CommandConflict.class,
        () -> objects.put("a".repeat(64) + "/" + "b".repeat(64), new byte[] {1}));
  }

  @Test
  void catalogueUsesServerPagination() {
    for (int i = 0; i < 10; i++) {
      var c = input("Page fixture " + i);
      documents.upload(
          "sophia",
          new UploadDocument(
              "Page " + String.format("%02d", i) + ".txt",
              c.mediaType(),
              c.contentBase64(),
              c.reason(),
              c.idempotencyKey()),
          "page");
    }
    assertEquals(10, documents.list("sophia", "Page", 0, 8).total());
    assertEquals(2, documents.list("sophia", "Page", 1, 8).items().size());
    assertEquals(
        "Page 08.txt", documents.list("sophia", "Page", 1, 8).items().getFirst().filename());
  }

  @Test
  void documentQuotaFailsBeforeStorageAndScan() {
    jdbc.update(
        "insert into employee_document select 'atlas',md5(n::text)::uuid::text,'E003','Quota.txt','text/plain',1,'QUARANTINED','fixture','Quota fixture',now(),repeat('a',64)||'/'||repeat('b',64),repeat('b',64),'fixture','quota-'||n from generate_series(1,100)n");
    jdbc.update(
        "insert into employee_document_audit(tenant,document_id,action,actor,recorded_at,reason,correlation_id) select d.tenant,d.id,a.action,'fixture',now(),'Quota fixture','quota' from employee_document d cross join (values('UPLOADED'),('QUARANTINED'))a(action)");
    assertThrows(
        CommandConflict.class, () -> documents.upload("sophia", input("Quota blocked"), "quota"));
    assertEquals(100, documents.list("sophia", "Quota", 0, 8).total());
    assertDoesNotThrow(() -> jdbc.execute("set constraints all immediate"));
  }

  @Test
  void databaseRejectsDocumentMetadataMutation() {
    var d = documents.upload("sophia", input("Immutable fixture"), "immutable");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("update employee_document set status='AVAILABLE' where id=?", d.id()));
  }
}
