package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.api.WorkforceAccess;
import com.atlas.modules.documents.adapter.out.integration.CoreHrDocumentIdentity;
import com.atlas.modules.documents.adapter.out.persistence.*;
import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.in.*;
import com.atlas.modules.documents.application.port.out.*;
import com.atlas.modules.documents.application.usecase.DocumentTemplatesService;
import com.atlas.modules.documents.domain.model.DocumentTemplatePolicy;
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
class DocumentTemplateWorkflowTest {
  @Autowired DocumentTemplatesUseCases templates;
  @Autowired DocumentsUseCases documents;
  @Autowired JdbcTemplate jdbc;
  @Autowired JdbcClient db;
  @Autowired WorkforceAccess workforce;

  CreateDocumentTemplate template(String code) {
    return new CreateDocumentTemplate(
        code,
        "Synthetic company form",
        "Name:\nDeclaration:",
        true,
        LocalDate.now(ZoneOffset.UTC),
        "Synthetic template configuration",
        UUID.randomUUID().toString());
  }

  DocumentView upload(String user, String text) {
    return documents.upload(
        user,
        new UploadDocument(
            "Evidence.txt",
            "text/plain",
            Base64.getEncoder()
                .encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "Synthetic evidence",
            UUID.randomUUID().toString()),
        "upload");
  }

  LinkDocumentEvidence link(DocumentView d) {
    return new LinkDocumentEvidence(
        d.id(),
        BigDecimal.valueOf(d.version()),
        "Synthetic classification",
        UUID.randomUUID().toString());
  }

  @Test
  void configurationAndLinksAreImmutableAuditedAndReplayStable() {
    var c = template("DECLARATION");
    var t = templates.create("amelia", c, "create-template");
    assertEquals(t, templates.create("amelia", c, "replay"));
    assertEquals("create-template", t.correlationId());
    assertEquals("MISSING", templates.completeness("sophia", null).items().getFirst().state());
    var d = upload("sophia", "Synthetic completed declaration");
    var l = link(d);
    var e = templates.link("sophia", t.code(), l, "link-evidence");
    assertEquals(e, templates.link("sophia", t.code(), l, "replay"));
    var complete = templates.completeness("amelia", "E003");
    assertEquals("PRESENT", complete.items().getFirst().state());
    assertEquals(d.id(), complete.items().getFirst().rootId());
    assertEquals(List.of(e), templates.history("sophia", null));
    assertEquals("link-evidence", e.correlationId());
    assertDoesNotThrow(() -> jdbc.execute("set constraints all immediate"));
  }

  @Test
  void latestRejectedVersionBlocksCompletenessWithoutHistoricalFallback() {
    templates.create("amelia", template("LATEST"), "configure");
    var d = upload("sophia", "Synthetic clean");
    var command = link(d);
    var saved = templates.link("sophia", "LATEST", command, "link");
    var malware = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    var latest =
        documents.version(
            "sophia",
            d.id(),
            new VersionDocument(
                1,
                new UploadDocument(
                    "Blocked.txt",
                    "text/plain",
                    Base64.getEncoder().encodeToString(malware.getBytes()),
                    "Synthetic malware replacement",
                    UUID.randomUUID().toString())),
            "blocked");
    assertEquals("REJECTED", latest.status());
    var check = templates.completeness("sophia", null).items().getFirst();
    assertEquals("BLOCKED", check.state());
    assertEquals(2, check.version());
    assertEquals(saved, templates.link("sophia", "LATEST", command, "old replay"));
    assertEquals("AVAILABLE", d.status());
  }

  @Test
  void deniesRoleRecordTenantAndChangedCommandKeys() {
    var c = template("PRIVATE");
    for (var user : List.of("sophia", "marcus"))
      assertThrows(AccessDenied.class, () -> templates.create(user, c, "denied"));
    templates.create("amelia", c, "create");
    var d = upload("sophia", "Synthetic private");
    for (var user : List.of("marcus", "outsider")) {
      assertThrows(AccessDenied.class, () -> templates.link(user, c.code(), link(d), "denied"));
      assertThrows(AccessDenied.class, () -> templates.completeness(user, "E003"));
      assertThrows(AccessDenied.class, () -> templates.history(user, "E003"));
    }
    assertTrue(templates.templates("outsider").isEmpty());
    assertThrows(
        CommandConflict.class,
        () ->
            templates.create(
                "amelia",
                new CreateDocumentTemplate(
                    c.code(),
                    "Changed",
                    c.body(),
                    true,
                    c.effectiveDate(),
                    c.reason(),
                    c.idempotencyKey()),
                "conflict"));
    var command = link(d);
    templates.link("amelia", c.code(), command, "link");
    assertThrows(
        CommandConflict.class,
        () ->
            templates.link(
                "amelia",
                c.code(),
                new LinkDocumentEvidence(
                    d.id(), BigDecimal.ONE, "Changed", command.idempotencyKey()),
                "conflict"));
    assertThrows(
        CommandConflict.class, () -> templates.link("sophia", c.code(), link(d), "duplicate"));
  }

  @Test
  void requiresExactVersionAndCleanCurrentDocumentAndSingleClassification() {
    templates.create("amelia", template("ONE"), "create");
    templates.create("amelia", template("TWO"), "create");
    var d = upload("sophia", "Old");
    var updated =
        documents.version(
            "sophia",
            d.id(),
            new VersionDocument(
                1,
                new UploadDocument(
                    "Current.txt",
                    "text/plain",
                    Base64.getEncoder().encodeToString("Current".getBytes()),
                    "Synthetic update",
                    UUID.randomUUID().toString())),
            "version");
    assertThrows(CommandConflict.class, () -> templates.link("sophia", "ONE", link(d), "stale"));
    for (var n : Arrays.asList(null, new BigDecimal("1.5")))
      assertThrows(
          InputValidation.class,
          () ->
              templates.link(
                  "sophia",
                  "ONE",
                  new LinkDocumentEvidence(
                      updated.id(), n, "Synthetic reason", UUID.randomUUID().toString()),
                  "invalid"));
    templates.link("sophia", "ONE", link(updated), "link");
    assertThrows(
        CommandConflict.class,
        () -> templates.link("sophia", "TWO", link(updated), "duplicate chain"));
  }

  @Test
  void policyUsesInjectedDateAndOptionalAndFutureStates() {
    var today = LocalDate.now(ZoneOffset.UTC);
    templates.create(
        "amelia",
        new CreateDocumentTemplate(
            "FUTURE",
            "Future",
            "Synthetic text",
            true,
            today.plusDays(1),
            "Synthetic config",
            UUID.randomUUID().toString()),
        "create");
    templates.create(
        "amelia",
        new CreateDocumentTemplate(
            "OPTIONAL",
            "Optional",
            "Synthetic text",
            false,
            today,
            "Synthetic config",
            UUID.randomUUID().toString()),
        "create");
    assertEquals(
        List.of("NOT_YET_EFFECTIVE", "OPTIONAL"),
        templates.completeness("sophia", null).items().stream()
            .map(DocumentCompleteness.Item::state)
            .toList());
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> operation) {
            return operation.get();
          }
        };
    var tomorrow =
        new DocumentTemplatesService(
            new JdbcDocumentTemplateStore(db),
            new JdbcDocumentStore(db),
            new CoreHrDocumentIdentity(workforce),
            tx,
            Clock.fixed(
                today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    assertEquals("MISSING", tomorrow.completeness("sophia", null).items().getFirst().state());
    assertEquals("BLOCKED", DocumentTemplatePolicy.completeness(true, today, today, "QUARANTINED"));
    assertEquals("PRESENT", DocumentTemplatePolicy.completeness(true, today, today, "AVAILABLE"));
  }

  @Test
  void databaseRejectsEvidenceOwnerMismatch() {
    templates.create("amelia", template("INTEGRITY"), "create");
    var d = upload("sophia", "Synthetic document");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "insert into document_template_evidence values(?,?,?,?,?,?,?,?,?,?)",
                "atlas",
                "INTEGRITY",
                "E001",
                d.id(),
                1,
                "amelia",
                "Synthetic bad owner",
                java.sql.Timestamp.from(Instant.now()),
                "bad-owner",
                UUID.randomUUID().toString()));
  }

  @Test
  void databaseRejectsConfigurationMutation() {
    templates.create("amelia", template("IMMUTABLE"), "create");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("update document_template set required=false where code='IMMUTABLE'"));
  }

  @Test
  void databaseRejectsEvidenceMutation() {
    templates.create("amelia", template("AUDITED"), "create");
    var d = upload("sophia", "Synthetic evidence");
    templates.link("sophia", "AUDITED", link(d), "link");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("delete from document_template_evidence where code='AUDITED'"));
  }

  @Test
  void validatesConfigurationAndCapacityBeforePartialReport() {
    assertThrows(
        InputValidation.class,
        () ->
            templates.create(
                "amelia",
                new CreateDocumentTemplate(
                    "bad",
                    "Title",
                    "Body",
                    true,
                    LocalDate.now(),
                    "Reason",
                    UUID.randomUUID().toString()),
                "invalid"));
    assertThrows(
        InputValidation.class,
        () ->
            templates.create(
                "amelia",
                new CreateDocumentTemplate(
                    "VALID",
                    "Title",
                    "Body",
                    null,
                    LocalDate.now(),
                    "Reason",
                    UUID.randomUUID().toString()),
                "invalid"));
    jdbc.update(
        "insert into document_template select 'atlas','CAP'||n,'Synthetic','Synthetic',true,current_date,'amelia','Synthetic fixture',now(),'fixture','fixture-key-'||n from generate_series(1,101)n");
    assertThrows(CommandConflict.class, () -> templates.templates("sophia"));
    assertThrows(CommandConflict.class, () -> templates.completeness("sophia", null));
  }
}
