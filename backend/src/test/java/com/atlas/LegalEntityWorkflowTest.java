package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.LegalEntityModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.domain.model.*;
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
class LegalEntityWorkflowTest {
  @Autowired LegalEntitiesUseCases entities;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;

  Actor hr() {
    return people.actor("amelia");
  }

  Create command(String code, LocalDate date) {
    return new Create(
        new Input(code, "Synthetic Entity 李", "GB", "GBP", "Europe/London", date),
        "Declared synthetic reference",
        UUID.randomUUID().toString());
  }

  @Test
  void createsImmutableReferenceWithReceiptAndStableReplay() {
    var c = command("SYNTH-ONE", LocalDate.now(ZoneOffset.UTC));
    var e = entities.create(hr(), c, "entity-correlation");
    assertEquals(e, entities.create(hr(), c, "retry"));
    assertEquals("amelia", e.createdBy());
    assertEquals("entity-correlation", e.correlationId());
    assertEquals(1, entities.list(hr(), new Query("SYNTH-ONE", "CURRENT", 0, 10)).total());
    assertEquals(0, entities.list(hr(), new Query("%", "ALL", 0, 10)).total());
    assertEquals(0, entities.list(hr(), new Query("", "PLANNED", 0, 10)).total());
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            entities.create(
                hr(), new Create(c.entity(), "Changed reason", c.idempotencyKey()), "conflict"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            entities.create(
                hr(), command("SYNTH-ONE", c.entity().effectiveFrom()), "duplicate-code"));
  }

  @Test
  void scopesPagesAndVocabularyAndRejectsOrdinaryRoles() {
    for (int i = 0; i < 12; i++)
      entities.create(
          hr(),
          command("PAGE-" + String.format("%02d", i), LocalDate.now(ZoneOffset.UTC).plusDays(1)),
          "page");
    var page = entities.list(hr(), new Query("PAGE-", "PLANNED", 1, 5));
    assertEquals(12, page.total());
    assertEquals("PAGE-05", page.items().getFirst().entity().code());
    assertEquals(5, page.items().size());
    var other = new Actor("other-hr", "other", "E900", "Other HR", "HR");
    assertEquals(0, entities.list(other, new Query("", "ALL", 0, 10)).total());
    for (String user : List.of("sophia", "marcus", "outsider")) {
      var actor = people.actor(user);
      assertThrows(
          ChangeRequest.Denied.class, () -> entities.list(actor, new Query("", "ALL", 0, 10)));
      assertThrows(ChangeRequest.Denied.class, () -> entities.options(actor));
      assertThrows(
          ChangeRequest.Denied.class,
          () -> entities.create(actor, command("DENIED", LocalDate.now(ZoneOffset.UTC)), "denied"));
    }
    assertTrue(entities.options(hr()).timeZones().contains("UTC"));
    assertThrows(IllegalArgumentException.class, () -> new Query("", "BAD", 0, 10));
  }

  @Test
  void sqlGuardsCreationSnapshotAndRejectsEditDeleteAndTruncate() {
    entities.create(hr(), command("IMMUTABLE", LocalDate.now(ZoneOffset.UTC)), "immutable");
    for (String statement :
        List.of(
            "update hr_legal_entity set reason='changed'",
            "delete from hr_legal_entity",
            "truncate hr_legal_entity cascade")) {
      db.execute("savepoint entity_guard");
      assertThrows(org.springframework.dao.DataAccessException.class, () -> db.execute(statement));
      db.execute("rollback to savepoint entity_guard");
    }
    db.execute("savepoint entity_shape");
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            db.update(
                "insert into hr_legal_entity select (jsonb_populate_record(null::hr_legal_entity,to_jsonb(e)||jsonb_build_object('code','BAD-SQL','country_code','gb','idempotency_key','bad-sql-country'))).* from hr_legal_entity e limit 1"));
    db.execute("rollback to savepoint entity_shape");
  }

  @Test
  void lateReplayPreservesMicrosecondReceiptAndCapIsBounded() {
    com.atlas.modules.corehr.application.port.out.UnitOfWork direct =
        new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> work) {
            return work.get();
          }
        };
    var store =
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityStore(
            org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource()));
    var instant = Instant.parse("2026-10-19T12:00:00.123456789Z");
    var service =
        new com.atlas.modules.corehr.application.usecase.LegalEntitiesService(
            store, direct, direct, Clock.fixed(instant, ZoneOffset.UTC));
    var c = command("LATE", LocalDate.of(2026, 10, 19));
    var receipt = service.create(hr(), c, "first");
    assertEquals(Instant.parse("2026-10-19T12:00:00.123456Z"), receipt.recordedAt());
    var later =
        new com.atlas.modules.corehr.application.usecase.LegalEntitiesService(
            store, direct, direct, Clock.fixed(instant.plusSeconds(86400 * 7), ZoneOffset.UTC));
    assertEquals(receipt, later.create(hr(), c, "late-replay"));
    for (int n = 1; n < 200; n++)
      service.create(
          hr(), command("CAP-" + String.format("%03d", n), c.entity().effectiveFrom()), "cap");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            service.create(hr(), command("CAP-EXCEEDED", c.entity().effectiveFrom()), "over-cap"));
    assertEquals(200, service.list(hr(), new Query("", "ALL", 0, 50)).total());
    assertEquals(receipt, later.create(hr(), c, "replay-at-cap"));
  }
}
