package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
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
class CalendarAssignmentWorkflowTest {
  @Autowired PeopleUseCases people;
  @Autowired CalendarsUseCases calendars;
  @Autowired CalendarAmendmentsUseCases amendments;
  @Autowired CalendarRetirementUseCases retirements;
  @Autowired EmployeeCalendarUseCases previews;
  @Autowired JdbcTemplate db;
  @Autowired RehireUseCases rehires;

  String key() {
    return UUID.randomUUID().toString();
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  Actor hr() {
    return people.actor("amelia");
  }

  Actor reviewer() {
    return people.actor("olivia");
  }

  Actor self() {
    return people.actor("sophia");
  }

  void create(String code) {
    calendars.create(
        hr(),
        new CalendarModels.Create(
            new CalendarModels.Input(
                code,
                "Assigned company calendar",
                "Europe/London",
                today().toString(),
                today().plusDays(30).toString(),
                List.of(1, 2, 3, 4, 5, 6, 7).stream().map(BigDecimal::valueOf).toList(),
                List.of(today().plusDays(2).toString())),
            "Synthetic company reference",
            key()),
        "calendar-create");
  }

  Submit command(String code, LocalDate date, String key) {
    return new Submit(
        "E003",
        "calendarCode",
        code,
        date,
        "Assign company calendar reference",
        people.profile(hr(), "E003").version(),
        key);
  }

  ChangeRequest submit(String code, LocalDate date) {
    return people.submit(hr(), command(code, date, key()), "calendar-assignment");
  }

  void approve(ChangeRequest r) {
    people.decide(
        reviewer(),
        r.id(),
        new Decide("APPROVED", "Independent calendar assignment review"),
        "calendar-approved");
  }

  CalendarAmendmentModels.Amend amend(int version, LocalDate date) {
    return new CalendarAmendmentModels.Amend(
        BigDecimal.valueOf(version),
        date.toString(),
        List.of(1, 2, 3, 4, 5, 6, 7).stream().map(BigDecimal::valueOf).toList(),
        List.of(),
        "Changed company policy",
        key());
  }

  @Test
  void approvedReferencePreviewAndUnassignmentRetainHistoryAndFreezePolicy() {
    create("ASSIGN-BASE");
    var before = people.profile(self(), "E003");
    var c = command("ASSIGN-BASE", today(), key());
    var r = people.submit(hr(), c, "assigned");
    assertEquals(0, previews.review(hr(), r.id()).expectedPolicyVersion());
    approve(r);
    assertEquals(r.id(), people.submit(hr(), c, "late-replay").id());
    var e = people.profile(self(), "E003");
    assertEquals("ASSIGN-BASE", e.calendarCode());
    assertEquals(before.email(), e.email());
    var p = previews.preview(self(), "E003", today().plusDays(2).toString());
    assertEquals("COMPANY_CLOSURE", p.preview().classification());
    assertFalse(p.preview().working());
    assertEquals(0, p.preview().revision());
    assertEquals(
        p, previews.preview(people.actor("marcus"), "E003", today().plusDays(2).toString()));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> amendments.amend(hr(), "ASSIGN-BASE", amend(0, today().plusDays(1)), "blocked"));
    assertThrows(
        ChangeRequest.Conflict.class,
        () ->
            retirements.retire(
                hr(),
                "ASSIGN-BASE",
                new CalendarRetirementModels.Retire(
                    BigDecimal.ZERO,
                    today().plusDays(3).toString(),
                    "Attempt retired assigned policy",
                    key()),
                "blocked"));
    var context = amendments.context(hr(), "ASSIGN-BASE");
    assertTrue(context.assignmentRecorded());
    assertNull(context.draft());
    assertNull(context.retirementDraft());
    var clear = submit("", today());
    approve(clear);
    assertEquals("", people.profile(self(), "E003").calendarCode());
    assertThrows(
        ChangeRequest.Conflict.class, () -> previews.preview(self(), "E003", today().toString()));
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from hr_calendar_assignment where employee_id='E003'", Integer.class));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> amendments.amend(hr(), "ASSIGN-BASE", amend(0, today().plusDays(1)), "still-frozen"));
  }

  @Test
  void onlyIndependentHrCanChangeWhileCurrentScopeControlsPreview() {
    create("ASSIGN-ROLE");
    var c = command("ASSIGN-ROLE", today(), key());
    for (var actor : List.of(self(), people.actor("marcus"), people.actor("outsider")))
      assertThrows(ChangeRequest.Denied.class, () -> people.submit(actor, c, "denied"));
    var r = people.submit(hr(), c, "role");
    for (var actor : List.of(hr(), self(), people.actor("marcus")))
      assertThrows(
          ChangeRequest.Denied.class,
          () ->
              people.decide(actor, r.id(), new Decide("APPROVED", "Forbidden reviewer"), "denied"));
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) values('calendar-hr-alias','atlas','E001','Alias','HR')");
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            people.decide(
                people.actor("calendar-hr-alias"),
                r.id(),
                new Decide("APPROVED", "Alias reviewer"),
                "alias"));
    approve(r);
    assertThrows(
        ChangeRequest.Denied.class,
        () -> previews.preview(people.actor("outsider"), "E003", today().toString()));
    for (var actor : List.of(self(), people.actor("marcus")))
      assertThrows(ChangeRequest.Denied.class, () -> previews.review(actor, r.id()));
    db.update("update hr_employee set manager_id='E001' where id='E003' and tenant='atlas'");
    assertThrows(
        ChangeRequest.Denied.class,
        () -> previews.preview(people.actor("marcus"), "E003", today().toString()));
  }

  @Test
  void changedPolicyAndRetirementInvalidatePendingApprovalButNotRejectionOrReplay() {
    create("ASSIGN-CHANGED");
    var c = command("ASSIGN-CHANGED", today().plusDays(1), key());
    var r = people.submit(hr(), c, "pending");
    amendments.amend(hr(), "ASSIGN-CHANGED", amend(0, today().plusDays(1)), "policy");
    assertTrue(previews.review(hr(), r.id()).policyChanged());
    assertThrows(ChangeRequest.Conflict.class, () -> approve(r));
    people.decide(
        reviewer(),
        r.id(),
        new Decide("REJECTED", "Policy changed; fresh request required"),
        "reject");
    assertEquals(r.id(), people.submit(hr(), c, "replay-rejected").id());
    var second = submit("ASSIGN-CHANGED", today().plusDays(2));
    retirements.retire(
        hr(),
        "ASSIGN-CHANGED",
        new CalendarRetirementModels.Retire(
            BigDecimal.ONE, today().plusDays(3).toString(), "Retire before approval", key()),
        "retire");
    assertTrue(previews.review(hr(), second.id()).retirementRecorded());
    assertThrows(ChangeRequest.Conflict.class, () -> approve(second));
    people.decide(reviewer(), second.id(), new Decide("REJECTED", "Retired reference"), "reject");
    assertEquals(
        0, db.queryForObject("select count(*) from hr_calendar_assignment", Integer.class));
  }

  @Test
  void futureAssignmentAndPolicyRevisionApplyOnlyAtTheirDate() {
    create("ASSIGN-FUTURE");
    var am = amend(0, today().plusDays(1));
    amendments.amend(hr(), "ASSIGN-FUTURE", am, "policy");
    var r = submit("ASSIGN-FUTURE", today().plusDays(1));
    approve(r);
    assertEquals("", people.profile(self(), "E003").calendarCode());
    assertThrows(
        ChangeRequest.Conflict.class, () -> previews.preview(self(), "E003", today().toString()));
    assertEquals(
        1, previews.preview(self(), "E003", today().plusDays(1).toString()).preview().revision());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> previews.preview(self(), "E003", today().plusDays(31).toString()));
    assertEquals(
        amendments.context(hr(), "ASSIGN-FUTURE").history().getFirst(),
        amendments.amend(hr(), "ASSIGN-FUTURE", am, "old-policy-replay"));
  }

  @Test
  void rejectsUnknownRetiredAndOutOfWindowWithNoAssignment() {
    assertThrows(ChangeRequest.Validation.class, () -> submit("MISSING", today()));
    create("ASSIGN-BOUNDS");
    assertThrows(
        ChangeRequest.Validation.class, () -> submit("ASSIGN-BOUNDS", today().plusDays(31)));
    var r = submit("ASSIGN-BOUNDS", today());
    var second = submit("ASSIGN-BOUNDS", today().plusDays(1));
    approve(second);
    assertThrows(ChangeRequest.Conflict.class, () -> approve(r));
    people.decide(reviewer(), r.id(), new Decide("REJECTED", "Stale employee version"), "reject");
  }

  @Test
  void episodeOwnershipPreventsAnOldAssignmentCarryingIntoRehire() {
    var jdbc = JdbcClient.create(db.getDataSource());
    var ps = new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(jdbc);
    com.atlas.modules.corehr.application.port.out.UnitOfWork tx =
        new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> s) {
            return s.get();
          }
        };
    var clock =
        Clock.fixed(today().minusDays(20).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    var oldCalendars =
        new com.atlas.modules.corehr.application.usecase.CalendarsService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarStore(jdbc),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarAmendmentStore(jdbc),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarRetirementStore(jdbc),
            tx,
            tx,
            clock);
    oldCalendars.create(
        hr(),
        new CalendarModels.Create(
            new CalendarModels.Input(
                "ASSIGN-EPISODE",
                "Historical company policy",
                "Europe/London",
                today().minusDays(20).toString(),
                today().plusDays(30).toString(),
                List.of(1, 2, 3, 4, 5, 6, 7).stream().map(BigDecimal::valueOf).toList(),
                List.of()),
            "Historical synthetic policy",
            key()),
        "historical-calendar");
    var org =
        new com.atlas.modules.corehr.application.usecase.OrganisationService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcOrganisationStore(jdbc),
            tx,
            clock);
    var pastPeople =
        new com.atlas.modules.corehr.application.usecase.PeopleService(ps, tx, clock, org);
    var assignment =
        pastPeople.submit(
            hr(),
            new Submit(
                "E003",
                "calendarCode",
                "ASSIGN-EPISODE",
                today().minusDays(20),
                "Historical episode assignment",
                pastPeople.profile(hr(), "E003").version(),
                key()),
            "historical-assignment");
    pastPeople.decide(
        reviewer(),
        assignment.id(),
        new Decide("APPROVED", "Independent historical assignment"),
        "historical-approved");
    var oldEntities =
        new com.atlas.modules.corehr.application.usecase.LegalEntitiesService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityStore(jdbc),
            tx,
            tx,
            clock);
    oldEntities.create(
        hr(),
        new LegalEntityModels.Create(
            new LegalEntityModels.Input(
                "ENTITY-EPISODE",
                "Historical declared entity",
                "GB",
                "GBP",
                "Europe/London",
                today().minusDays(20)),
            "Historical supplied entity",
            key()),
        "old-entity");
    var entityRequest =
        pastPeople.submit(
            hr(),
            new Submit(
                "E003",
                "legalEntityCode",
                "ENTITY-EPISODE",
                today().minusDays(20),
                "Historical declared entity assignment",
                pastPeople.profile(hr(), "E003").version(),
                key()),
            "old-entity-submit");
    pastPeople.decide(
        reviewer(),
        entityRequest.id(),
        new Decide("APPROVED", "Independent historical entity review"),
        "old-entity-approved");
    for (var request : people.requests(hr()))
      if (request.employeeId().equals("E003") && request.status().equals("SUBMITTED"))
        people.decide(
            hr(),
            request.id(),
            new Decide("REJECTED", "Resolve synthetic pending request"),
            "resolve");
    var rs = new com.atlas.modules.corehr.adapter.out.persistence.JdbcResignationStore(jdbc);
    var resign =
        new com.atlas.modules.corehr.application.usecase.ResignationService(rs, ps, tx, tx, clock);
    var ends =
        new com.atlas.modules.corehr.application.usecase.EmploymentEndService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcEmploymentEndStore(jdbc),
            rs,
            ps,
            tx,
            tx,
            clock);
    var version = people.profile(hr(), "E003").version();
    var departure =
        resign.submit(
            "sophia",
            new ResignationCommands.Submit(
                today().minusDays(10),
                "Historical synthetic departure",
                BigDecimal.valueOf(version),
                key()),
            "departure");
    resign.review(
        "amelia",
        departure.id(),
        new ResignationCommands.Review("APPROVED", "Independent departure", BigDecimal.ZERO, key()),
        "departure-approved");
    ends.confirm(
        "amelia",
        departure.id(),
        new EmploymentEndModels.Confirm(
            "Historical synthetic end", BigDecimal.valueOf(version), key()),
        "ended");
    var proposed =
        rehires.submit(
            "amelia",
            "E003",
            new RehireModels.Submit(
                today().plusDays(5),
                "Returning engineer",
                "Engineering",
                "London",
                "E002",
                "Synthetic return",
                BigDecimal.valueOf(rehires.context("amelia", "E003").employeeVersion()),
                key()),
            "rehire");
    rehires.review(
        "olivia",
        "E003",
        proposed.id(),
        new RehireModels.Review(
            "APPROVED",
            "Independent return",
            BigDecimal.valueOf(rehires.context("amelia", "E003").employeeVersion()),
            key()),
        "rehire-approved");
    assertEquals(
        "ASSIGN-EPISODE", ps.employee(hr(), "E003", today().minusDays(15), false).calendarCode());
    assertEquals("", ps.employee(hr(), "E003", today().plusDays(5), false).calendarCode());
    assertEquals(
        "ENTITY-EPISODE",
        ps.employee(hr(), "E003", today().minusDays(15), false).legalEntityCode());
    assertEquals("", ps.employee(hr(), "E003", today().plusDays(5), false).legalEntityCode());
    var returned =
        new com.atlas.modules.corehr.application.usecase.EmployeeCalendarService(
            ps,
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarStore(jdbc),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarAmendmentStore(jdbc),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarRetirementStore(jdbc),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcEmployeeCalendarHistoryStore(
                jdbc),
            tx,
            Clock.fixed(
                today().plusDays(5).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    var retained = returned.history(hr(), "E003", new EmployeeCalendarModels.HistoryQuery(0, 8));
    assertEquals(today().plusDays(5), retained.currentEpisodeStart());
    assertEquals(1, retained.total());
    assertEquals("ASSIGN-EPISODE", retained.items().getFirst().calendarCode());
    assertTrue(retained.items().getFirst().episodeStart().isBefore(retained.currentEpisodeStart()));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> previews.preview(hr(), "E003", today().plusDays(5).toString()));
  }

  @Test
  void historyPagesOnlyApprovedLedgerRowsWithBusinessAndRecordingDates() {
    create("HISTORY-ONE");
    create("HISTORY-TWO");
    assertEquals(
        0, previews.history(self(), "E003", new EmployeeCalendarModels.HistoryQuery(0, 8)).total());
    for (int i = 0; i < 11; i++)
      approve(submit(i % 2 == 0 ? "HISTORY-ONE" : "HISTORY-TWO", today()));
    var pending = submit("", today().plusDays(1));
    assertEquals(
        11,
        previews.history(self(), "E003", new EmployeeCalendarModels.HistoryQuery(0, 8)).total());
    people.decide(
        reviewer(), pending.id(), new Decide("REJECTED", "Retain pending exclusion"), "reject");
    approve(submit("", today().plusDays(1)));
    var first = previews.history(self(), "E003", new EmployeeCalendarModels.HistoryQuery(0, 8));
    var last = previews.history(self(), "E003", new EmployeeCalendarModels.HistoryQuery(1, 8));
    assertEquals(12, first.total());
    assertEquals(8, first.items().size());
    assertEquals(4, last.items().size());
    assertEquals(
        first, previews.history(self(), "E003", new EmployeeCalendarModels.HistoryQuery(0, 8)));
    var clear = first.items().getFirst();
    assertEquals("", clear.calendarCode());
    assertEquals("", clear.calendarName());
    assertEquals("", clear.timeZone());
    assertEquals(today().plusDays(1), clear.effectiveDate());
    assertEquals(today(), clear.recordedAt().atZone(ZoneOffset.UTC).toLocalDate());
    assertEquals(first.currentEpisodeStart(), clear.episodeStart());
    assertEquals(today(), first.asOf());
    assertEquals("Assigned company calendar", first.items().get(1).calendarName());
    assertEquals("Europe/London", first.items().get(1).timeZone());
    var ids = new HashSet<String>();
    first.items().forEach(i -> assertTrue(ids.add(i.versionId())));
    last.items().forEach(i -> assertTrue(ids.add(i.versionId())));
    assertEquals(12, ids.size());
    var beyond =
        previews.history(self(), "E003", new EmployeeCalendarModels.HistoryQuery(100000, 50));
    assertEquals(12, beyond.total());
    assertTrue(beyond.items().isEmpty());
    assertEquals(
        first,
        previews.history(
            people.actor("marcus"), "E003", new EmployeeCalendarModels.HistoryQuery(0, 8)));
  }

  @Test
  void historyRechecksCurrentScopeEvenWhenLedgerIsEmpty() {
    var q = new EmployeeCalendarModels.HistoryQuery(0, 8);
    assertThrows(
        ChangeRequest.Denied.class, () -> previews.history(people.actor("outsider"), "E003", q));
    assertThrows(ChangeRequest.Denied.class, () -> previews.history(self(), "E004", q));
    assertThrows(ChangeRequest.Denied.class, () -> previews.history(hr(), "MISSING", q));
    create("HISTORY-SCOPE");
    approve(submit("HISTORY-SCOPE", today()));
    assertEquals(1, previews.history(people.actor("marcus"), "E003", q).total());
    db.update("update hr_employee set manager_id='E001' where id='E003' and tenant='atlas'");
    assertThrows(
        ChangeRequest.Denied.class, () -> previews.history(people.actor("marcus"), "E003", q));
    assertEquals(1, previews.history(self(), "E003", q).total());
  }

  @Test
  void qualifiedLedgerAndPolicyGuardsRejectMutationAndBypass() {
    create("ASSIGN-SQL");
    approve(submit("ASSIGN-SQL", today()));
    db.execute("set local search_path=pg_catalog");
    for (var statement :
        List.of(
            "update atlas_test.hr_calendar_assignment set calendar_code=calendar_code",
            "delete from atlas_test.hr_calendar_assignment",
            "truncate atlas_test.hr_calendar_assignment",
            "update atlas_test.hr_calendar_request_policy set policy_version=policy_version",
            "truncate atlas_test.hr_calendar_request_policy",
            "update atlas_test.hr_version set value=value where field='calendarCode'",
            "delete from atlas_test.hr_version where field='calendarCode'")) {
      db.execute("savepoint calendar_sql");
      var ex =
          assertThrows(
              org.springframework.dao.DataAccessException.class, () -> db.execute(statement));
      assertTrue(ex.getMostSpecificCause().getMessage().contains("append only"));
      db.execute("rollback to savepoint calendar_sql");
    }
    db.execute("savepoint policy_sql");
    var ex =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () ->
                db.update(
                    "insert into atlas_test.hr_work_calendar_retirement(tenant,code,calendar_version,effective_date,created_by,reason,recorded_at,correlation_id,idempotency_key) values('atlas','ASSIGN-SQL',0,current_date,'amelia','Forbidden assigned retirement',now(),'sql',?)",
                    key()));
    assertTrue(ex.getMostSpecificCause().getMessage().contains("freezes calendar policy"));
    db.execute("rollback to savepoint policy_sql");
  }
}
