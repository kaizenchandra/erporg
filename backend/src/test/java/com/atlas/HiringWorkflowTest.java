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
class HiringWorkflowTest {
  @Autowired HiringUseCases hiring;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate jdbc;

  Actor author() {
    return people.actor("amelia");
  }

  Actor reviewer() {
    return people.actor("olivia");
  }

  SubmitHire command(String email) {
    return new SubmitHire(
        "Zoe Synthetic",
        email,
        "+1 202 555 8888",
        "Test Analyst",
        "Design",
        "London, UK",
        "E002",
        LocalDate.now(ZoneOffset.UTC),
        "Synthetic hire",
        UUID.randomUUID().toString());
  }

  @Test
  void hireCreatesOneEmployeeOnlyAfterIndependentApproval() {
    var c = command("newhire@example.test");
    var r = hiring.submit(author(), c, "submitted");
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from hr_employee where email=?", Integer.class, c.email()));
    assertEquals(r.id(), hiring.submit(author(), c, "replay").id());
    assertThrows(
        ChangeRequest.Denied.class,
        () -> hiring.decide(author(), r.id(), new Decide("APPROVED", "Self"), "denied"));
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            hiring.decide(
                new Actor(
                    "another-identity",
                    author().tenant(),
                    author().employeeId(),
                    "Same person",
                    "HR"),
                r.id(),
                new Decide("APPROVED", "Same employee"),
                "denied"));
    var approved =
        hiring.decide(reviewer(), r.id(), new Decide("APPROVED", "Checked by HR"), "approved");
    assertEquals("APPROVED", approved.status());
    assertTrue(approved.employeeId().startsWith("E-"));
    var e = people.profile(author(), approved.employeeId());
    assertEquals(c.name(), e.name());
    assertEquals("Onboarding", e.status());
    assertEquals(c.startDate(), e.startDate());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from hr_employee where email=?", Integer.class, c.email()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from hr_employee_event where employee_id=? and delivery_status='PENDING'",
            Integer.class,
            e.id()));
    assertEquals(2, hiring.history(author(), r.id()).size());
    assertEquals("approved", hiring.history(author(), r.id()).getLast().correlationId());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> hiring.decide(reviewer(), r.id(), new Decide("APPROVED", "Repeated"), "duplicate"));
  }

  @Test
  void rejectionNeverCreatesEmployeeOrProvisioningEvent() {
    var r = hiring.submit(author(), command("rejected@example.test"), "submitted");
    var rejected =
        hiring.decide(
            reviewer(), r.id(), new Decide("REJECTED", "Hiring budget not approved"), "rejected");
    assertEquals("REJECTED", rejected.status());
    assertNull(rejected.employeeId());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from hr_employee where email='rejected@example.test'", Integer.class));
    assertEquals(0, jdbc.queryForObject("select count(*) from hr_employee_event", Integer.class));
  }

  @Test
  void duplicatesAndChangedReplayCannotCreateTwoEmployees() {
    var first = command("duplicate@example.test");
    var r = hiring.submit(author(), first, "one");
    var second = hiring.submit(author(), command("duplicate@example.test"), "two");
    hiring.decide(reviewer(), r.id(), new Decide("APPROVED", "One only"), "approved");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            hiring.decide(reviewer(), second.id(), new Decide("APPROVED", "Duplicate"), "blocked"));
    assertEquals(
        "SUBMITTED",
        hiring.hires(author(), new RequestQuery("SUBMITTED", 0, 100)).items().stream()
            .filter(h -> h.id().equals(second.id()))
            .findFirst()
            .orElseThrow()
            .status());
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            hiring.submit(
                author(),
                new SubmitHire(
                    first.name(),
                    "changed@example.test",
                    first.phone(),
                    first.title(),
                    first.department(),
                    first.location(),
                    first.managerId(),
                    first.startDate(),
                    first.reason(),
                    first.idempotencyKey()),
                "conflict"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> hiring.submit(author(), command("AMELIA.BENNETT@EXAMPLE.TEST"), "existing-email"));
  }

  @Test
  void nonHrAndOtherTenantCannotReadOrDecideHire() {
    var r = hiring.submit(author(), command("privatehire@example.test"), "submitted");
    for (var user : List.of("marcus", "sophia", "outsider")) {
      var actor = people.actor(user);
      assertThrows(
          ChangeRequest.Denied.class, () -> hiring.hires(actor, new RequestQuery("ALL", 0, 12)));
      assertThrows(ChangeRequest.Denied.class, () -> hiring.history(actor, r.id()));
      assertThrows(
          ChangeRequest.Denied.class,
          () -> hiring.submit(actor, command("forged@example.test"), "denied"));
    }
    var other = new Actor("other-hr", "other", "E900", "Other HR", "HR");
    assertTrue(hiring.hires(other, new RequestQuery("ALL", 0, 12)).items().isEmpty());
    assertThrows(
        ChangeRequest.Denied.class,
        () -> hiring.decide(other, r.id(), new Decide("APPROVED", "Wrong tenant"), "denied"));
  }

  @Test
  void validationAndReferenceFailuresDoNotPersistHires() {
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            hiring.submit(
                author(),
                new SubmitHire(
                    "",
                    "bad email",
                    "bad phone",
                    "",
                    "",
                    "",
                    "",
                    null,
                    "Reason",
                    UUID.randomUUID().toString()),
                "invalid"));
    var c = command("reference@example.test");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            hiring.submit(
                author(),
                new SubmitHire(
                    c.name(),
                    c.email(),
                    c.phone(),
                    c.title(),
                    "Unknown",
                    c.location(),
                    c.managerId(),
                    c.startDate(),
                    c.reason(),
                    c.idempotencyKey()),
                "department"));
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            hiring.submit(
                author(),
                new SubmitHire(
                    c.name(),
                    c.email(),
                    c.phone(),
                    c.title(),
                    c.department(),
                    c.location(),
                    "E900",
                    c.startDate(),
                    c.reason(),
                    c.idempotencyKey()),
                "wrong-manager"));
    assertEquals(0, jdbc.queryForObject("select count(*) from hr_hire_request", Integer.class));
  }
}
