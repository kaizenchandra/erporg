package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.adapter.out.persistence.*;
import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.EmploymentEndModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.EmploymentEndService;
import com.atlas.modules.corehr.domain.model.*;
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
class RehireWorkflowTest {
  @Autowired EmploymentEndUseCases ends;
  @Autowired RehireUseCases rehires;
  @Autowired OrganisationUseCases organisation;
  @Autowired ResignationUseCases resignations;
  @Autowired PeopleUseCases people;
  @Autowired ExitTaskUseCases tasks;
  @Autowired JdbcTemplate db;

  String key() {
    return UUID.randomUUID().toString();
  }

  EmploymentEnd former() {
    return former(true);
  }

  EmploymentEnd former(boolean resolvePending) {
    if (resolvePending)
      for (var request : people.requests(people.actor("amelia")))
        if (request.employeeId().equals("E003") && request.status().equals("SUBMITTED"))
          people.decide(
              people.actor("amelia"),
              request.id(),
              new Commands.Decide(
                  "REJECTED", "Resolve pre-existing synthetic request before rehire"),
              "resolved");
    var e = people.profile(people.actor("sophia"), "E003");
    var client = JdbcClient.create(db.getDataSource());
    var ps = new JdbcPeopleStore(client);
    var rs = new JdbcResignationStore(client);
    var tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> work) {
            return work.get();
          }
        };
    var clock =
        Clock.fixed(
            LocalDate.now(ZoneOffset.UTC).minusDays(20).atTime(12, 0).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC);
    var resign =
        new com.atlas.modules.corehr.application.usecase.ResignationService(rs, ps, tx, tx, clock);
    var end = new EmploymentEndService(new JdbcEmploymentEndStore(client), rs, ps, tx, tx, clock);
    var r =
        resign.submit(
            "sophia",
            new ResignationCommands.Submit(
                LocalDate.now(ZoneOffset.UTC).minusDays(10),
                "Historical synthetic departure",
                BigDecimal.valueOf(e.version()),
                key()),
            "submitted");
    resign.review(
        "amelia",
        r.id(),
        new ResignationCommands.Review("APPROVED", "Independent", BigDecimal.ZERO, key()),
        "approved");
    return end.confirm(
        "amelia",
        r.id(),
        new Confirm("Agreed prior end", BigDecimal.valueOf(e.version()), key()),
        "ended");
  }

  RehireModels.Submit command(LocalDate date) {
    return new RehireModels.Submit(
        date,
        "Returning engineer",
        "Engineering",
        "London",
        "E002",
        "Return into onboarding",
        BigDecimal.valueOf(rehires.context("amelia", "E003").employeeVersion()),
        key());
  }

  RehireModels.Review review(String decision) {
    return new RehireModels.Review(
        decision,
        "Independent rehire review",
        BigDecimal.valueOf(rehires.context("amelia", "E003").employeeVersion()),
        key());
  }

  RehireUseCases at(Instant time) {
    var client = JdbcClient.create(db.getDataSource());
    var tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> s) {
            return s.get();
          }
        };
    return new com.atlas.modules.corehr.application.usecase.RehireService(
        new JdbcRehireStore(client),
        new JdbcPeopleStore(client),
        organisation,
        tx,
        tx,
        Clock.fixed(time, ZoneOffset.UTC));
  }

  @Test
  void approvalCreatesSecondEpisodeAndDatedJobButNeverRestoresLegacyAccess() {
    var end = former();
    var before = people.profile(people.actor("amelia"), "E003");
    var proposed = command(LocalDate.now(ZoneOffset.UTC).plusDays(5));
    var c =
        new RehireModels.Submit(
            proposed.startDate(),
            proposed.title(),
            proposed.department(),
            proposed.location(),
            "E001",
            proposed.reason(),
            proposed.version(),
            proposed.idempotencyKey());
    var r = rehires.submit("amelia", "E003", c, "requested");
    assertEquals(r, rehires.submit("amelia", "E003", c, "replay"));
    assertEquals(1, rehires.context("amelia", "E003").episodes().size());
    var decision = review("APPROVED");
    var approved = rehires.review("olivia", "E003", r.id(), decision, "approved");
    assertEquals(approved, rehires.review("olivia", "E003", r.id(), decision, "replay"));
    var context = rehires.context("amelia", "E003");
    assertTrue(context.legacyAccessBlocked());
    assertEquals(2, context.episodes().size());
    assertEquals("SCHEDULED", context.episodes().get(1).state());
    assertEquals(end.inactiveDate(), context.episodes().getFirst().inactiveDate());
    assertEquals(before.startDate(), context.episodes().getFirst().startDate());
    var store = new JdbcPeopleStore(JdbcClient.create(db.getDataSource()));
    var hr = people.actor("amelia");
    assertEquals("Ended", store.employee(hr, "E003", c.startDate().minusDays(1), false).status());
    var projected = store.employee(hr, "E003", c.startDate(), false);
    assertEquals("Onboarding", projected.status());
    assertEquals(c.startDate(), projected.startDate());
    assertEquals(c.title(), projected.title());
    assertEquals(c.location(), projected.location());
    assertEquals("E001", projected.managerId());
    assertEquals(
        0,
        store
            .directory(
                PeopleReadScope.of(people.actor("marcus")),
                new DirectoryQuery("E003", "", "", 0, 12, "asc"),
                c.startDate())
            .total());
    assertEquals(before.email(), projected.email());
    assertEquals(before.phone(), projected.phone());
    assertEquals("", projected.positionCode());
    assertThrows(ChangeRequest.Denied.class, () -> store.actor("sophia", c.startDate()));
    assertEquals(
        "ONBOARDING",
        at(c.startDate().atStartOfDay(ZoneOffset.UTC).toInstant())
            .context("amelia", "E003")
            .episodes()
            .get(1)
            .state());
    assertEquals(
        3,
        db.queryForObject(
            "select count(*) from hr_rehire_handoff where status='PENDING'", Integer.class));
    assertEquals(
        before.startDate(),
        db.queryForObject(
            "select start_date from hr_employee where tenant='atlas' and id='E003'",
            LocalDate.class));
    assertEquals(
        1,
        store
            .directory(
                com.atlas.modules.corehr.application.model.PeopleReadScope.of(hr),
                new DirectoryQuery("E003", "", "Onboarding", 0, 12, "asc"),
                c.startDate())
            .total());
    assertEquals(
        "Returning engineer",
        new JdbcOrganisationChartStore(JdbcClient.create(db.getDataSource()))
            .find(PeopleReadScope.of(hr), "E003", c.startDate())
            .orElseThrow()
            .node()
            .title());
    db.execute("set constraints all immediate");
  }

  @Test
  void effectiveEndIsRequiredAndPendingRejectionDoesNotChangeThePriorEpisode() {
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> rehires.submit("amelia", "E003", command(LocalDate.now(ZoneOffset.UTC)), "active"));
    former();
    var r = rehires.submit("amelia", "E003", command(LocalDate.now(ZoneOffset.UTC)), "submitted");
    rehires.review("olivia", "E003", r.id(), review("REJECTED"), "rejected");
    var context = rehires.context("amelia", "E003");
    assertEquals("Ended", context.employmentStatus());
    assertEquals(1, context.episodes().size());
    assertFalse(context.legacyAccessBlocked());
    assertEquals(0, db.queryForObject("select count(*) from hr_rehire_handoff", Integer.class));
    rehires.submit("amelia", "E003", command(LocalDate.now(ZoneOffset.UTC).plusDays(1)), "fresh");
  }

  @Test
  void roleTenantTargetAndAliasReviewAreDenied() {
    former();
    var c = command(LocalDate.now(ZoneOffset.UTC));
    for (var user : List.of("sophia", "marcus", "outsider")) {
      assertThrows(ChangeRequest.Denied.class, () -> rehires.context(user, "E003"));
      assertThrows(ChangeRequest.Denied.class, () -> rehires.submit(user, "E003", c, "denied"));
    }
    var r = rehires.submit("amelia", "E003", c, "submitted");
    var decision = review("APPROVED");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> rehires.review("amelia", "E003", r.id(), decision, "self"));
    db.update("update identity_membership set employee_id='E001',role='HR' where subject='marcus'");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> rehires.review("marcus", "E003", r.id(), decision, "alias"));
    db.update("update identity_membership set employee_id='E003',role='HR' where subject='olivia'");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> rehires.review("olivia", "E003", r.id(), decision, "returning-hr"));
  }

  @Test
  void exactVersionsDatesReplayAndStaleSnapshotFailSafely() {
    former();
    var c = command(LocalDate.now(ZoneOffset.UTC));
    for (var v : Arrays.asList(null, new BigDecimal("0.5"), new BigDecimal("-1")))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              rehires.submit(
                  "amelia",
                  "E003",
                  new RehireModels.Submit(
                      c.startDate(),
                      c.title(),
                      c.department(),
                      c.location(),
                      c.managerId(),
                      c.reason(),
                      v,
                      key()),
                  "invalid"));
    for (var d :
        Arrays.asList(
            null,
            LocalDate.now(ZoneOffset.UTC).minusDays(1),
            LocalDate.now(ZoneOffset.UTC).plusYears(2)))
      assertThrows(
          ChangeRequest.Validation.class,
          () -> rehires.submit("amelia", "E003", command(d), "invalid-date"));
    var r = rehires.submit("amelia", "E003", c, "submitted");
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            rehires.submit(
                "amelia",
                "E003",
                new RehireModels.Submit(
                    c.startDate(),
                    "Different title",
                    c.department(),
                    c.location(),
                    c.managerId(),
                    c.reason(),
                    c.version(),
                    c.idempotencyKey()),
                "conflict"));
    db.update("update hr_employee set version=version+1 where tenant='atlas' and id='E003'");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> rehires.review("olivia", "E003", r.id(), review("APPROVED"), "stale"));
    rehires.review("olivia", "E003", r.id(), review("REJECTED"), "closed-stale");
  }

  @Test
  void overdueRequestCannotBeApprovedButMayBeRejected() {
    former();
    var c = command(LocalDate.now(ZoneOffset.UTC));
    var r = rehires.submit("amelia", "E003", c, "submitted");
    var tomorrow = at(c.startDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    assertThrows(
        ChangeRequest.Validation.class,
        () -> tomorrow.review("olivia", "E003", r.id(), review("APPROVED"), "past"));
    tomorrow.review("olivia", "E003", r.id(), review("REJECTED"), "rejected");
  }

  @Test
  void referencesSelfManagerAndFuturePendingChangesBlockApproval() {
    former();
    var c = command(LocalDate.now(ZoneOffset.UTC));
    for (var manager : List.of("E003", "E004"))
      assertThrows(
          ChangeRequest.Validation.class,
          () ->
              rehires.submit(
                  "amelia",
                  "E003",
                  new RehireModels.Submit(
                      c.startDate(),
                      c.title(),
                      c.department(),
                      c.location(),
                      manager,
                      c.reason(),
                      c.version(),
                      key()),
                  "manager"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            rehires.submit(
                "amelia",
                "E003",
                new RehireModels.Submit(
                    c.startDate(),
                    c.title(),
                    "Missing department",
                    c.location(),
                    c.managerId(),
                    c.reason(),
                    c.version(),
                    key()),
                "department"));
    var r = rehires.submit("amelia", "E003", c, "submitted");
    db.update(
        "update identity_membership set role='EMPLOYEE' where tenant='atlas' and employee_id='E002'");
    assertThrows(
        ChangeRequest.Validation.class,
        () -> rehires.review("olivia", "E003", r.id(), review("APPROVED"), "manager-changed"));
  }

  @Test
  void twentyRejectedAttemptsPreserveHistoryAndBlockMore() {
    former();
    for (int i = 0; i < 20; i++) {
      var r = rehires.submit("amelia", "E003", command(LocalDate.now(ZoneOffset.UTC)), "submitted");
      rehires.review("olivia", "E003", r.id(), review("REJECTED"), "rejected");
    }
    assertEquals(20, rehires.context("amelia", "E003").history().size());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> rehires.submit("amelia", "E003", command(LocalDate.now(ZoneOffset.UTC)), "limit"));
  }

  @Test
  void immutableDecisionEpisodeAndHandoffsReconcile() {
    former();
    var r = rehires.submit("amelia", "E003", command(LocalDate.now(ZoneOffset.UTC)), "submitted");
    db.execute("savepoint rehire_guard");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "insert into hr_rehire_decision(tenant,rehire_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id,idempotency_key) values('atlas',?,'REJECTED','amelia','E001','Self',?,now(),'forbidden',?)",
                r.id(),
                r.inputVersion(),
                key()));
    db.execute("rollback to savepoint rehire_guard");
    rehires.review("olivia", "E003", r.id(), review("APPROVED"), "approved");
    for (var table :
        List.of("hr_rehire", "hr_rehire_decision", "hr_employment_episode", "hr_rehire_handoff")) {
      db.execute("savepoint rehire_immutable");
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> db.update("delete from " + table));
      db.execute("rollback to savepoint rehire_immutable");
    }
    db.execute("set constraints all immediate");
  }

  @Test
  void pendingPreExitChangesMustBeResolvedBeforeRehire() {
    former(false);
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> rehires.submit("amelia", "E003", command(LocalDate.now(ZoneOffset.UTC)), "pending"));
    people.decide(
        people.actor("amelia"),
        "demo-request-001",
        new Commands.Decide("REJECTED", "Resolve pending prior change"),
        "resolved");
    rehires.submit("amelia", "E003", command(LocalDate.now(ZoneOffset.UTC)), "ready");
  }

  @Test
  void approvedFutureJobChangesCannotBeSilentlyOverwrittenByRehire() {
    var e = people.profile(people.actor("amelia"), "E003");
    var change =
        people.submit(
            people.actor("amelia"),
            new Commands.Submit(
                "E003",
                "title",
                "Previously approved future job",
                LocalDate.now(ZoneOffset.UTC).plusDays(20),
                "Future job",
                e.version(),
                key()),
            "future-job");
    people.decide(
        people.actor("olivia"),
        change.id(),
        new Commands.Decide("APPROVED", "Independent future job"),
        "approved");
    former();
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            rehires.submit(
                "amelia",
                "E003",
                command(LocalDate.now(ZoneOffset.UTC).plusDays(1)),
                "would-overwrite"));
  }

  @Test
  void importedReportingCycleFailsClosedWithTheCorrectFormField() {
    former();
    db.update("update hr_employee set manager_id='E003' where tenant='atlas' and id='E002'");
    var error =
        assertThrows(
            ChangeRequest.Validation.class,
            () ->
                rehires.submit("amelia", "E003", command(LocalDate.now(ZoneOffset.UTC)), "cycle"));
    assertTrue(error.fields().containsKey("managerId"));
  }
}
