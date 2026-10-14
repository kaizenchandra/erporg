package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.*;
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
class OrganisationWorkflowTest {
  @Autowired OrganisationUseCases organisation;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient client;

  Actor hr() {
    return people.actor("amelia");
  }

  CreateDepartment command(String code, String name, String parent, LocalDate date) {
    return new CreateDepartment(
        code,
        name,
        parent,
        "CC-TEST",
        date,
        "Synthetic org configuration",
        UUID.randomUUID().toString());
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  @Test
  void creationAuditReplayAndConflictingInput() {
    var c = command("TEST-PLATFORM", "Test Platform", null, today());
    var first = organisation.create(hr(), c, "org-submit");
    assertEquals(first, organisation.create(hr(), c, "org-repeat"));
    var tomorrow =
        new com.atlas.modules.corehr.application.usecase.OrganisationService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcOrganisationStore(client),
            new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
              public <T> T run(java.util.function.Supplier<T> operation) {
                return operation.get();
              }
            },
            Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));
    assertEquals(first, tomorrow.create(hr(), c, "retry-next-day"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from hr_department_audit where code='TEST-PLATFORM'", Integer.class));
    assertTrue(
        organisation.history(hr()).stream()
            .anyMatch(
                a ->
                    a.code().equals(c.code())
                        && a.correlationId().equals("org-submit")
                        && a.reason().equals(c.reason())));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            organisation.create(
                hr(),
                new CreateDepartment(
                    c.code(),
                    c.name(),
                    c.parentCode(),
                    c.costCentre(),
                    c.effectiveFrom(),
                    "Changed reason",
                    c.idempotencyKey()),
                "changed"));
  }

  @Test
  void actorAndTenantScopesDenyConfigurationAccess() {
    var c = command("TEST-PRIVATE", "Private Test Department", null, today());
    for (var user : List.of("sophia", "marcus", "outsider"))
      assertThrows(
          ChangeRequest.Denied.class, () -> organisation.create(people.actor(user), c, "denied"));
    organisation.create(hr(), c, "created");
    assertTrue(
        organisation.departments(people.actor("outsider")).stream()
            .noneMatch(d -> d.code().equals(c.code())));
    assertThrows(ChangeRequest.Denied.class, () -> organisation.history(people.actor("marcus")));
  }

  @Test
  void parentsAndDatesAreValidatedAndKnownTransferApplies() {
    var parent =
        organisation.create(
            hr(), command("TEST-PARENT", "Test Parent", null, today().plusDays(10)), "parent");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            organisation.create(
                hr(), command("TEST-CHILD", "Test Child", parent.code(), today()), "invalid-date"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            organisation.create(
                hr(), command("TEST-MISSING", "Test Missing", "MISSING", today()), "missing"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            organisation.create(
                hr(), command("TEST-SELF", "Test Self", "TEST-SELF", today()), "self"));
    var manager = people.actor("marcus");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            people.submit(
                manager,
                new Submit(
                    "E003",
                    "department",
                    "Unknown",
                    today(),
                    "Unknown transfer",
                    0,
                    UUID.randomUUID().toString()),
                "unknown"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            people.submit(
                manager,
                new Submit(
                    "E003",
                    "department",
                    parent.name(),
                    today(),
                    "Too early",
                    0,
                    UUID.randomUUID().toString()),
                "early"));
    var target =
        organisation.create(hr(), command("TEST-TARGET", "Test Target", null, today()), "target");
    var request =
        people.submit(
            manager,
            new Submit(
                "E003",
                "department",
                target.name(),
                today(),
                "Approved transfer",
                0,
                UUID.randomUUID().toString()),
            "transfer");
    people.decide(hr(), request.id(), new Decide("APPROVED", "HR checked department"), "approved");
    assertEquals(target.name(), people.profile(people.actor("sophia"), "E003").department());
    assertEquals(
        "CC-TEST",
        organisation.departments(hr()).stream()
            .filter(d -> d.code().equals(target.code()))
            .findFirst()
            .orElseThrow()
            .costCentre());
  }

  @Test
  void duplicateCodesAndNamesHaveDatabaseProtection() {
    var c = command("TEST-UNIQUE", "Test Unique", null, today());
    organisation.create(hr(), c, "created");
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            organisation.create(
                hr(), command("TEST-UNIQUE", "Another Name", null, today()), "duplicate"));
  }
}
