package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.InitialIdentityModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.shared.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest(properties = "INITIAL_IDENTITY_ISSUER=http://127.0.0.1:8088/realms/atlas")
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InitialIdentityWorkflowTest {
  static final String SCHEMA =
      "atlas_initial_identity_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://localhost:5438/atlas?currentSchema=" + SCHEMA);
    r.add("spring.flyway.default-schema", () -> SCHEMA);
    r.add("spring.flyway.schemas", () -> SCHEMA);
  }

  @Autowired InitialIdentityUseCases identity;
  @Autowired PeopleUseCases people;
  @Autowired HiringUseCases hiring;
  @Autowired JdbcTemplate db;

  String key() {
    return UUID.randomUUID().toString();
  }

  String hire(LocalDate date) {
    var h =
        hiring.submit(
            people.actor("amelia"),
            new SubmitHire(
                "Initial Identity Synthetic",
                key() + "@example.test",
                "+12025550191",
                "Analyst",
                "Design",
                "London, UK",
                "E002",
                date,
                "Synthetic new hire",
                key()),
            "test");
    return hiring
        .decide(
            people.actor("olivia"),
            h.id(),
            new Commands.Decide("APPROVED", "Independent hire review"),
            "test")
        .employeeId();
  }

  Submit command(String e, String sub) {
    return new Submit(
        BigDecimal.valueOf(identity.context("amelia", e).employeeVersion()),
        sub,
        "Provider subject verified independently",
        key());
  }

  Review approve() {
    return new Review("APPROVE", "Independent provider verification", key());
  }

  @Test
  void reviewedBindingGrantsOnlyEmployeeAndRequiresCorrectIssuer() {
    String e = hire(LocalDate.now(ZoneOffset.UTC)), sub = key();
    var c = command(e, sub);
    var b = identity.submit("amelia", e, c, "submit");
    assertEquals(b, identity.submit("amelia", e, c, "replay"));
    assertThrows(AccessDenied.class, () -> people.actor(sub));
    assertThrows(
        AccessDenied.class, () -> identity.review("amelia", b.request().id(), approve(), "self"));
    var review = approve();
    var approved = identity.review("olivia", b.request().id(), review, "review");
    assertEquals(approved, identity.review("olivia", b.request().id(), review, "replay"));
    assertEquals(e, people.actor(sub).employeeId());
    assertEquals("EMPLOYEE", people.actor(sub).role());
    identity.verifyIssuer(sub, "http://127.0.0.1:8088/realms/atlas");
    assertThrows(AccessDenied.class, () -> identity.verifyIssuer(sub, ""));
    assertThrows(
        AccessDenied.class, () -> identity.verifyIssuer(sub, "https://wrong.example.test"));
    assertThrows(AccessDenied.class, () -> identity.context(sub, e));
    assertThrows(
        InputValidation.class, () -> identity.submit("amelia", e, command(e, key()), "second"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "delete from hr_initial_identity_decision where tenant='atlas' and request_id=?",
                b.request().id()));
  }

  @Test
  void startDateGatesAccessAndReassignmentCannotEscalate() {
    String e = hire(LocalDate.now(ZoneOffset.UTC).plusDays(3)), sub = key();
    var b = identity.submit("amelia", e, command(e, sub), "submit");
    identity.review("olivia", b.request().id(), approve(), "review");
    assertThrows(AccessDenied.class, () -> people.actor(sub));
    db.update("update identity_membership set employee_id='E001',role='HR' where subject=?", sub);
    assertThrows(AccessDenied.class, () -> people.actor(sub));
  }

  @Test
  void aliasSelfReviewStaleRevisionAndCrossTenantDenied() {
    String e = hire(LocalDate.now(ZoneOffset.UTC)), sub = key();
    var b = identity.submit("amelia", e, command(e, sub), "submit");
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) select ?,tenant,employee_id,name,role from identity_membership where subject='amelia'",
        sub + "-alias");
    assertThrows(
        AccessDenied.class,
        () -> identity.review(sub + "-alias", b.request().id(), approve(), "alias"));
    assertThrows(AccessDenied.class, () -> identity.context("outsider", e));
    db.update(
        "update hr_employee set version=version+1 where tenant=? and id=?",
        people.actor("amelia").tenant(),
        e);
    assertThrows(
        InputValidation.class,
        () -> identity.review("olivia", b.request().id(), approve(), "stale"));
    identity.review(
        "olivia",
        b.request().id(),
        new Review("REJECT", "Employee changed; prepare a new mapping", key()),
        "reject");
    assertThrows(AccessDenied.class, () -> people.actor(sub));
  }

  @Test
  void concurrentReviewsHaveExactlyOneWinner() throws Exception {
    String e = hire(LocalDate.now(ZoneOffset.UTC)), sub = key();
    var b = identity.submit("amelia", e, command(e, sub), "submit");
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var work =
          (Callable<Boolean>)
              () -> {
                start.await();
                try {
                  identity.review("olivia", b.request().id(), approve(), "race");
                  return true;
                } catch (CommandConflict x) {
                  return false;
                }
              };
      var a = pool.submit(work);
      var c = pool.submit(work);
      start.countDown();
      assertNotEquals(a.get(), c.get());
    }
    assertEquals(e, people.actor(sub).employeeId());
  }
}
