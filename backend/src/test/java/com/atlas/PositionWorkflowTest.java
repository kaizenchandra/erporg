package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
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
class PositionWorkflowTest {
  @Autowired OrganisationUseCases organisation;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient client;

  Actor hr() {
    return people.actor("amelia");
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  void department(LocalDate date) {
    organisation.create(
        hr(),
        new CreateDepartment(
            "POS-DEPT",
            "Position Test Department",
            null,
            "CC-TEST",
            date,
            "Test department",
            UUID.randomUUID().toString()),
        "dept");
  }

  CreatePosition command(String code, String title, LocalDate date) {
    return new CreatePosition(
        code,
        title,
        "POS-DEPT",
        date,
        "Synthetic position configuration",
        UUID.randomUUID().toString());
  }

  @Test
  void creationIsAtomicAuditedAndReplaySafeAcrossDateBoundary() {
    department(today());
    var c = command("POS-LEAD", " Lead analyst ", today());
    var p = organisation.createPosition(hr(), c, "position-create");
    assertEquals("Lead analyst", p.title());
    assertEquals(p, organisation.createPosition(hr(), c, "replay"));
    var tomorrow =
        new com.atlas.modules.corehr.application.usecase.OrganisationService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcOrganisationStore(client),
            new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
              public <T> T run(java.util.function.Supplier<T> operation) {
                return operation.get();
              }
            },
            Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));
    assertEquals(p, tomorrow.createPosition(hr(), c, "next-day"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from hr_position_audit where tenant=? and code=?",
            Integer.class,
            hr().tenant(),
            c.code()));
    assertTrue(
        organisation.positionHistory(hr()).stream()
            .anyMatch(
                a ->
                    a.code().equals(c.code())
                        && a.reason().equals(c.reason())
                        && a.correlationId().equals("position-create")));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            organisation.createPosition(
                hr(),
                new CreatePosition(
                    c.code(),
                    "Changed",
                    c.departmentCode(),
                    c.effectiveFrom(),
                    c.reason(),
                    c.idempotencyKey()),
                "conflict"));
  }

  @Test
  void rolesTenantAndDepartmentDatesAreEnforced() {
    department(today().plusDays(10));
    var c = command("POS-FUTURE", "Future role", today());
    assertThrows(
        ChangeRequest.Validation.class, () -> organisation.createPosition(hr(), c, "early"));
    for (String user : List.of("sophia", "marcus", "outsider"))
      assertThrows(
          ChangeRequest.Denied.class,
          () -> organisation.createPosition(people.actor(user), c, "denied"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            organisation.createPosition(
                new Actor("test", people.actor("outsider").tenant(), "E006", "Other HR", "HR"),
                command("POS-CROSS", "Cross tenant", today()),
                "cross"));
    organisation.createPosition(
        hr(), command("POS-FUTURE", "Future role", today().plusDays(10)), "future");
    assertEquals(
        0,
        organisation
            .positions(people.actor("outsider"), new PositionQuery("", "", 0, 8, "asc"))
            .total());
    assertThrows(
        ChangeRequest.Denied.class, () -> organisation.positionHistory(people.actor("marcus")));
  }

  @Test
  void searchFilteringSortingAndUncappedCountUseStablePages() {
    department(today());
    for (int i = 0; i < 12; i++)
      organisation.createPosition(
          hr(), command("POS-" + i, "Role " + String.format("%02d", i), today()), "page");
    var first = organisation.positions(hr(), new PositionQuery("Role", "POS-DEPT", 0, 8, "asc"));
    var last =
        organisation.positions(
            people.actor("sophia"), new PositionQuery("Role", "POS-DEPT", 1, 8, "asc"));
    assertEquals(12, first.total());
    assertEquals(8, first.items().size());
    assertEquals(4, last.items().size());
    assertEquals("Role 08", last.items().getFirst().title());
    assertEquals(
        "Role 11",
        organisation
            .positions(hr(), new PositionQuery("Role", "POS-DEPT", 0, 8, "desc"))
            .items()
            .getFirst()
            .title());
    assertEquals(
        0, organisation.positions(hr(), new PositionQuery("%", "POS-DEPT", 0, 8, "asc")).total());
    assertThrows(IllegalArgumentException.class, () -> new PositionQuery("", "", 0, 101, "asc"));
    assertThrows(IllegalArgumentException.class, () -> new PositionQuery("", "", 0, 8, "drop"));
  }

  @Test
  void positionAndAuditCannotBeChangedDirectly() {
    department(today());
    organisation.createPosition(hr(), command("POS-IMMUTABLE", "Fixed role", today()), "immutable");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "update hr_position set title='Changed' where tenant=? and code='POS-IMMUTABLE'",
                hr().tenant()));
  }

  @Test
  void databasePreventsDuplicatePositionCodes() {
    department(today());
    organisation.createPosition(hr(), command("POS-UNIQUE", "First role", today()), "first");
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            organisation.createPosition(
                hr(), command("POS-UNIQUE", "Second role", today()), "duplicate"));
  }
}
