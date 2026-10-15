package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.Decide;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
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
class OnboardingWorkflowTest {
  @Autowired HiringUseCases hiring;
  @Autowired PeopleUseCases people;
  @Autowired OrganisationUseCases organisation;
  @Autowired JdbcTemplate jdbc;
  @Autowired JdbcClient client;

  Actor hr() {
    return people.actor("amelia");
  }

  HireRequest hire(LocalDate date) {
    var input =
        new SubmitHire(
            "Onboarding Synthetic",
            "onboarding@example.test",
            "+1 202 555 8888",
            "Analyst",
            "Design",
            "London",
            "E002",
            date,
            "Synthetic onboarding",
            UUID.randomUUID().toString());
    var submitted = hiring.submit(hr(), input, "submit");
    return hiring.decide(
        people.actor("olivia"),
        submitted.id(),
        new Decide("APPROVED", "Independent HR approval"),
        "approve");
  }

  CompleteOnboarding command() {
    return new CompleteOnboarding("Joining readiness verified", 0L, UUID.randomUUID().toString());
  }

  @Test
  void completionIsAtomicReplayStableAndAudited() {
    var hire = hire(LocalDate.now(ZoneOffset.UTC));
    var c = command();
    var result = hiring.completeOnboarding(hr(), hire.id(), c, "complete");
    assertEquals("Onboarding", result.oldStatus());
    assertEquals("Active", result.newStatus());
    assertEquals(1, result.newVersion());
    assertEquals(result, hiring.completeOnboarding(hr(), hire.id(), c, "replay"));
    assertEquals("Active", people.profile(hr(), hire.employeeId()).status());
    assertEquals(1, people.profile(hr(), hire.employeeId()).version());
    assertEquals(1, hiring.employmentHistory(hr(), hire.employeeId()).size());
    assertEquals(
        "complete", hiring.employmentHistory(hr(), hire.employeeId()).getFirst().correlationId());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from hr_employee_event where employee_id=? and event_type='ONBOARDING_COMPLETED'",
            Integer.class,
            hire.employeeId()));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            hiring.completeOnboarding(
                hr(),
                hire.id(),
                new CompleteOnboarding("Changed reason", 0L, c.idempotencyKey()),
                "conflict"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> hiring.completeOnboarding(hr(), hire.id(), command(), "another"));
  }

  @Test
  void activationWaitsForStartDateAndUsesInjectedBusinessClock() {
    var start = LocalDate.now(ZoneOffset.UTC).plusDays(10);
    var hire = hire(start);
    var c = command();
    assertThrows(
        ChangeRequest.Conflict.class, () -> hiring.completeOnboarding(hr(), hire.id(), c, "early"));
    assertEquals("Onboarding", people.profile(hr(), hire.employeeId()).status());
    var future =
        new com.atlas.modules.corehr.application.usecase.HiringService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcHiringStore(client),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(client),
            organisation,
            new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
              public <T> T run(java.util.function.Supplier<T> operation) {
                return operation.get();
              }
            },
            Clock.fixed(start.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    var activated = future.completeOnboarding(hr(), hire.id(), c, "on-start");
    assertEquals(start, activated.effectiveDate());
    assertEquals(start.atStartOfDay(ZoneOffset.UTC).toInstant(), activated.recordedAt());
  }

  @Test
  void nonHrTenantAndStaleVersionDeniedHistoryReasonRedacted() {
    var hire = hire(LocalDate.now(ZoneOffset.UTC));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> hiring.completeOnboarding(people.actor("marcus"), hire.id(), command(), "manager"));
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            hiring.completeOnboarding(
                new Actor("other-hr", "other", "E900", "Other HR", "HR"),
                hire.id(),
                command(),
                "other"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            hiring.completeOnboarding(
                hr(),
                hire.id(),
                new CompleteOnboarding("Stale", 9L, UUID.randomUUID().toString()),
                "stale"));
    hiring.completeOnboarding(hr(), hire.id(), command(), "complete");
    assertNull(
        hiring.employmentHistory(people.actor("marcus"), hire.employeeId()).getFirst().reason());
    assertNotNull(hiring.employmentHistory(hr(), hire.employeeId()).getFirst().reason());
    assertThrows(
        ChangeRequest.Denied.class,
        () -> hiring.employmentHistory(people.actor("outsider"), hire.employeeId()));
  }

  @Test
  void unapprovedAndInvalidReasonDoNotChangeEmployees() {
    var input =
        new SubmitHire(
            "Pending Synthetic",
            "pending@example.test",
            "+1 202 555 9999",
            "Analyst",
            "Design",
            "London",
            "E002",
            LocalDate.now(ZoneOffset.UTC),
            "Submitted only",
            UUID.randomUUID().toString());
    var pending = hiring.submit(hr(), input, "pending");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> hiring.completeOnboarding(hr(), pending.id(), command(), "unapproved"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            hiring.completeOnboarding(
                hr(),
                pending.id(),
                new CompleteOnboarding(" ", 0L, UUID.randomUUID().toString()),
                "invalid"));
    assertEquals(
        0, jdbc.queryForObject("select count(*) from hr_onboarding_completion", Integer.class));
  }
}
