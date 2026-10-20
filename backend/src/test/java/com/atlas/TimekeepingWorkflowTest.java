package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.timekeeping.application.model.TimeModels.*;
import com.atlas.modules.timekeeping.application.port.in.TimeUseCases;
import com.atlas.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TimekeepingWorkflowTest {
  static final String SCHEMA =
      "atlas_time_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://localhost:5438/atlas?currentSchema=" + SCHEMA);
    r.add("spring.flyway.default-schema", () -> SCHEMA);
    r.add("spring.flyway.schemas", () -> SCHEMA);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
  }

  @Autowired TimeUseCases time;
  @Autowired JdbcTemplate db;

  String key() {
    return UUID.randomUUID().toString();
  }

  LocalDate monday(int weeks) {
    return LocalDate.now(ZoneOffset.UTC)
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .minusWeeks(weeks);
  }

  SaveSheet command(int weeks, String hours, long version) {
    var w = monday(weeks);
    var entries = new ArrayList<EntryInput>();
    for (int i = 0; i < 7; i++)
      entries.add(
          new EntryInput(w.plusDays(i), new BigDecimal(i == 0 ? hours : "0"), "DEMO-ENGINEERING"));
    return new SaveSheet(
        w, "Etc/UTC", BigDecimal.valueOf(version), entries, "Synthetic working hours", key());
  }

  @Test
  void dashboardUsesCurrentEpisodeAndCurrentTeamScopeWithoutInferringSchedule() {
    var w = monday(98);
    assertNull(time.dashboard("sophia", w).sheet());
    assertFalse(time.dashboard("sophia", w).canReview());
    assertEquals(0, time.dashboard("sophia", w).pendingApprovals());
    var saved = time.save("sophia", command(98, "1", 0), "dashboard-save");
    assertEquals(saved, time.dashboard("sophia", w).sheet());
    var before = time.dashboard("marcus", w).pendingApprovals();
    time.decide(
        "sophia",
        saved.id(),
        new Decision(BigDecimal.ZERO, "SUBMIT", "Review", key()),
        "dashboard-submit");
    assertEquals(before + 1, time.dashboard("marcus", w).pendingApprovals());
    assertNull(time.dashboard("outsider", w).sheet());
    assertEquals(0, time.dashboard("outsider", w).pendingApprovals());
  }

  @Test
  void concurrentCopyRetriesCreateExactlyOneDraftAndReceipt() throws Exception {
    var source = time.save("sophia", command(96, "1", 0), "concurrent-copy-source");
    var command =
        new CopySheet(source.id(), BigDecimal.ZERO, monday(95), "Actual hours reviewed", key());
    var gate = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var tasks = new ArrayList<Future<Sheet>>();
      for (int i = 0; i < 2; i++)
        tasks.add(
            executor.submit(
                () -> {
                  gate.await();
                  return time.copy("sophia", command, "copy-race");
                }));
      gate.countDown();
      var first = tasks.get(0).get(10, TimeUnit.SECONDS);
      assertEquals(first, tasks.get(1).get(10, TimeUnit.SECONDS));
      assertEquals(
          1,
          db.queryForObject(
              "select count(*) from time_audit where entity_id=? and action='COPIED'",
              Integer.class,
              first.id()));
      assertEquals(
          1,
          db.queryForObject(
              "select count(*) from time_command where command_key=?",
              Integer.class,
              command.idempotencyKey()));
    }
  }

  @Test
  void copiesAreOwnedVersionedIdempotentAndNeverOverwriteOrPost() {
    var source = time.save("sophia", command(91, "2", 0), "copy-source");
    var c =
        new CopySheet(
            source.id(), BigDecimal.ZERO, monday(90), "Review copied actual hours", key());
    assertThrows(AccessDenied.class, () -> time.copy("marcus", c, "forbidden-copy"));
    assertThrows(AccessDenied.class, () -> time.copy("outsider", c, "tenant-copy"));
    assertThrows(
        CommandConflict.class,
        () ->
            time.copy(
                "sophia",
                new CopySheet(source.id(), BigDecimal.ONE, monday(90), c.reason(), key()),
                "stale-copy"));
    var copied = time.copy("sophia", c, "copy-command");
    assertEquals("DRAFT", copied.status());
    assertEquals(120, copied.totalMinutes());
    assertEquals(monday(90), copied.entries().getFirst().date());
    assertEquals(0, time.sheet("sophia", copied.id()).allocatedMinutes());
    assertEquals(copied, time.copy("sophia", c, "copy-replay"));
    assertThrows(
        CommandConflict.class,
        () ->
            time.copy(
                "sophia",
                new CopySheet(source.id(), BigDecimal.ZERO, monday(90), c.reason(), key()),
                "existing-target"));
    assertThrows(
        CommandConflict.class,
        () ->
            time.copy(
                "sophia",
                new CopySheet(
                    source.id(), BigDecimal.ZERO, monday(89), c.reason(), c.idempotencyKey()),
                "changed-command"));
    time.configurePeriod(
        "amelia",
        controls(89, 1440, 10080, true, true, 0, Instant.now().plusSeconds(86400)),
        "closed-copy");
    assertThrows(
        CommandConflict.class,
        () ->
            time.copy(
                "sophia",
                new CopySheet(source.id(), BigDecimal.ZERO, monday(89), c.reason(), key()),
                "closed-target"));
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from time_audit where entity_id=? and action='COPIED'",
            Integer.class,
            copied.id()));
  }

  @Test
  void reportsAndExportsAreScopedBoundedAndReconcileToRealAllocations() {
    var s = time.save("sophia", command(93, "3", 0), "report-source");
    s =
        time.decide(
            "sophia",
            s.id(),
            new Decision(BigDecimal.ZERO, "SUBMIT", "Review", key()),
            "report-submit");
    var approved =
        time.decide(
            "marcus",
            s.id(),
            new Decision(BigDecimal.ONE, "APPROVE", "Verified", key()),
            "report-approve");
    var q = new ReportQuery(monday(93), monday(93), "E003", "APPROVED");
    var report = time.report("marcus", q);
    assertEquals(1, report.items().size());
    assertEquals(approved.id(), report.items().getFirst().id());
    assertEquals(180, report.totalMinutes());
    assertEquals(180, report.allocatedMinutes());
    assertEquals(report.items(), time.report("sophia", q).items());
    assertThrows(AccessDenied.class, () -> time.report("outsider", q));
    assertThrows(
        AccessDenied.class,
        () -> time.report("sophia", new ReportQuery(q.from(), q.through(), "E001", "ALL")));
    assertTrue(
        time.report("outsider", new ReportQuery(q.from(), q.through(), "", "ALL"))
            .items()
            .isEmpty());
    assertThrows(
        InputValidation.class,
        () ->
            time.report("sophia", new ReportQuery(q.through(), q.from().minusWeeks(1), "", "ALL")));
    assertThrows(
        InputValidation.class,
        () ->
            time.report(
                "sophia", new ReportQuery(q.from().minusWeeks(53), q.through(), "", "ALL")));
    assertThrows(
        InputValidation.class,
        () -> time.report("sophia", new ReportQuery(q.from(), q.through(), "", "INVALID")));
    assertEquals(report.items(), time.exportReport("marcus", q, "report-export").items());
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from time_audit where action='REPORT_EXPORTED' and correlation_id='report-export'",
            Integer.class));
    assertEquals(
        0,
        time.report("marcus", new ReportQuery(q.from(), q.through(), "E003", "RETURNED"))
            .items()
            .size());
  }

  @Test
  void concurrentPeriodKeyReuseAcrossWeeksHasOneWinnerAndOneConflict() throws Exception {
    var c = controls(65, 120, 600, true, false, 0, Instant.now().plusSeconds(86400));
    var other =
        new SavePeriod(
            monday(66),
            c.deadline(),
            c.dailyLimit(),
            c.weeklyLimit(),
            c.recallAllowed(),
            c.closed(),
            c.version(),
            c.reason(),
            c.idempotencyKey());
    var gate = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var tasks = new ArrayList<Future<String>>();
      for (var command : List.of(c, other))
        tasks.add(
            executor.submit(
                () -> {
                  gate.await();
                  try {
                    time.configurePeriod("amelia", command, "period-key-race");
                    return "OK";
                  } catch (CommandConflict e) {
                    return "CONFLICT";
                  }
                }));
      gate.countDown();
      var outcomes =
          List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS));
      assertEquals(1, outcomes.stream().filter("OK"::equals).count());
      assertEquals(1, outcomes.stream().filter("CONFLICT"::equals).count());
    }
  }

  @Test
  void bulkReviewIsAtomicScopedAndIdempotentForTheWholeSelection() {
    var first = time.save("sophia", command(62, "1", 0), "batch-one");
    var second = time.save("sophia", command(63, "2", 0), "batch-two");
    var eligible = first.id().compareTo(second.id()) < 0 ? first : second;
    var pending = eligible == first ? second : first;
    // The eligible sheet sorts first, proving a later failure rolls back an actual allocation.
    time.decide(
        "sophia", eligible.id(), new Decision(BigDecimal.ZERO, "SUBMIT", "First", key()), "submit");
    var selected =
        List.of(
            new SelectedSheet(eligible.id(), BigDecimal.ONE),
            new SelectedSheet(pending.id(), BigDecimal.ZERO));
    var batch = new BulkDecision(selected, "APPROVE", "Reviewed selected records", key());
    assertThrows(AccessDenied.class, () -> time.decideSelected("sophia", batch, "self"));
    assertThrows(AccessDenied.class, () -> time.decideSelected("outsider", batch, "tenant"));
    assertThrows(
        CommandConflict.class, () -> time.decideSelected("marcus", batch, "not-all-submitted"));
    assertEquals("SUBMITTED", time.sheet("sophia", eligible.id()).sheet().status());
    assertEquals(0, time.sheet("sophia", eligible.id()).allocatedMinutes());
    assertEquals(
        0,
        db.queryForObject(
            "select count(*) from time_command where subject=? and command_key like ?",
            Integer.class,
            "marcus",
            batch.idempotencyKey() + "-%"));
    time.decide(
        "sophia", pending.id(), new Decision(BigDecimal.ZERO, "SUBMIT", "Second", key()), "submit");
    var valid =
        new BulkDecision(
            List.of(
                new SelectedSheet(first.id(), BigDecimal.ONE),
                new SelectedSheet(second.id(), BigDecimal.ONE)),
            "APPROVE",
            "Checked all selected records",
            key());
    var result = time.decideSelected("marcus", valid, "approve-selected");
    assertEquals(2, result.size());
    assertEquals(result, time.decideSelected("marcus", valid, "retry-selected"));
    assertEquals(
        180,
        time.sheet("sophia", first.id()).allocatedMinutes()
            + time.sheet("sophia", second.id()).allocatedMinutes());
    assertThrows(
        CommandConflict.class,
        () ->
            time.decideSelected(
                "marcus",
                new BulkDecision(
                    List.of(valid.sheets().getFirst()),
                    valid.action(),
                    valid.comment(),
                    valid.idempotencyKey()),
                "changed-selection"));
    assertThrows(
        InputValidation.class,
        () ->
            time.decideSelected(
                "marcus",
                new BulkDecision(
                    List.of(valid.sheets().getFirst(), valid.sheets().getFirst()),
                    "APPROVE",
                    "Duplicate selection",
                    key()),
                "duplicate"));
  }

  @Test
  void concurrentRecallAndApprovalProduceExactlyOneCommittedOutcome() throws Exception {
    var draft = time.save("sophia", command(64, "1", 0), "race-draft");
    time.decide(
        "sophia",
        draft.id(),
        new Decision(BigDecimal.ZERO, "SUBMIT", "Review", key()),
        "race-submit");
    var gate = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var recall =
          executor.submit(
              () -> {
                gate.await();
                try {
                  return time.decide(
                          "sophia",
                          draft.id(),
                          new Decision(BigDecimal.ONE, "RECALL", "Recall pending", key()),
                          "race-recall")
                      .status();
                } catch (CommandConflict e) {
                  return "CONFLICT";
                }
              });
      var approve =
          executor.submit(
              () -> {
                gate.await();
                try {
                  return time.decide(
                          "marcus",
                          draft.id(),
                          new Decision(BigDecimal.ONE, "APPROVE", "Approve pending", key()),
                          "race-approve")
                      .status();
                } catch (CommandConflict e) {
                  return "CONFLICT";
                }
              });
      gate.countDown();
      var outcomes = List.of(recall.get(10, TimeUnit.SECONDS), approve.get(10, TimeUnit.SECONDS));
      assertEquals(1, outcomes.stream().filter("CONFLICT"::equals).count());
      var current = time.sheet("sophia", draft.id());
      assertEquals(
          current.sheet().status().equals("APPROVED") ? 60 : 0, current.allocatedMinutes());
    }
  }

  @Test
  void returnedHoursRequireCorrectionAndRecallRejectsStaleApproval() {
    var draft = time.save("sophia", command(60, "2", 0), "return-draft");
    var submitted =
        time.decide(
            "sophia",
            draft.id(),
            new Decision(BigDecimal.ZERO, "SUBMIT", "Review my time", key()),
            "return-submit");
    var returned =
        time.decide(
            "marcus",
            draft.id(),
            new Decision(BigDecimal.ONE, "RETURN", "Please fix allocation", key()),
            "return-review");
    assertEquals("RETURNED", returned.status());
    assertThrows(
        CommandConflict.class,
        () ->
            time.decide(
                "sophia",
                draft.id(),
                new Decision(BigDecimal.valueOf(2), "SUBMIT", "Unchanged", key()),
                "invalid"));
    var corrected = time.save("sophia", command(60, "1", 2), "correction");
    assertEquals("CORRECTED", corrected.status());
    var again =
        time.decide(
            "sophia",
            draft.id(),
            new Decision(BigDecimal.valueOf(3), "SUBMIT", "Corrected allocation", key()),
            "resubmit");
    var recall = new Decision(BigDecimal.valueOf(4), "RECALL", "Need another correction", key());
    var recalled = time.decide("sophia", draft.id(), recall, "recall");
    assertEquals("DRAFT", recalled.status());
    assertEquals(recalled, time.decide("sophia", draft.id(), recall, "retry"));
    assertThrows(
        CommandConflict.class,
        () ->
            time.decide(
                "marcus",
                draft.id(),
                new Decision(BigDecimal.valueOf(4), "APPROVE", "Stale review", key()),
                "stale"));
    assertEquals(0, time.sheet("sophia", draft.id()).allocatedMinutes());
    assertTrue(
        time.history("sophia", "E003", 0, 50).items().stream()
            .anyMatch(e -> e.action().equals("RETURN") && e.reason().contains("fix allocation")));
  }

  SavePeriod controls(
      int weeks,
      int daily,
      int weekly,
      boolean recall,
      boolean closed,
      long version,
      Instant deadline) {
    return new SavePeriod(
        monday(weeks),
        deadline.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        BigDecimal.valueOf(daily),
        BigDecimal.valueOf(weekly),
        recall,
        closed,
        BigDecimal.valueOf(version),
        "Synthetic weekly controls",
        key());
  }

  @Test
  void periodControlsEnforceAccessLimitsDeadlineClosureRecallAndImmutableHistory() {
    int week = 61;
    var c = controls(week, 120, 600, false, false, 0, Instant.now().plusSeconds(86400));
    assertThrows(AccessDenied.class, () -> time.configurePeriod("sophia", c, "deny"));
    assertThrows(AccessDenied.class, () -> time.configurePeriod("marcus", c, "deny"));
    assertThrows(
        InputValidation.class,
        () ->
            time.configurePeriod(
                "amelia",
                new SavePeriod(
                    c.week(),
                    c.deadline(),
                    c.dailyLimit(),
                    c.weeklyLimit(),
                    null,
                    false,
                    c.version(),
                    c.reason(),
                    key()),
                "null-recall"));
    assertThrows(
        InputValidation.class,
        () ->
            time.configurePeriod(
                "amelia",
                new SavePeriod(
                    c.week(),
                    c.deadline(),
                    c.dailyLimit(),
                    c.weeklyLimit(),
                    true,
                    null,
                    c.version(),
                    c.reason(),
                    key()),
                "null-closed"));
    var p = time.configurePeriod("amelia", c, "controls");
    assertEquals(p, time.configurePeriod("amelia", c, "retry"));
    assertNull(time.period("outsider", monday(week)).current());
    assertEquals(p, time.period("sophia", monday(week)).current());
    assertThrows(InputValidation.class, () -> time.save("sophia", command(week, "3", 0), "limit"));
    var draft = time.save("sophia", command(week, "2", 0), "within-limit");
    var submitted =
        time.decide(
            "sophia",
            draft.id(),
            new Decision(BigDecimal.ZERO, "SUBMIT", "Please review", key()),
            "submit");
    assertThrows(
        CommandConflict.class,
        () ->
            time.decide(
                "sophia",
                draft.id(),
                new Decision(BigDecimal.ONE, "RECALL", "Need to revise", key()),
                "disabled"));
    time.configurePeriod("amelia", controls(week, 120, 600, true, true, 1, c.deadline()), "close");
    assertThrows(
        CommandConflict.class,
        () ->
            time.decide(
                "marcus",
                draft.id(),
                new Decision(BigDecimal.ONE, "APPROVE", "Cannot post closed", key()),
                "closed"));
    var recalled =
        time.decide(
            "sophia",
            draft.id(),
            new Decision(BigDecimal.ONE, "RECALL", "Recall allowed even when closed", key()),
            "recall");
    assertThrows(
        CommandConflict.class, () -> time.save("sophia", command(week, "1", 2), "closed-save"));
    time.configurePeriod(
        "amelia",
        controls(
            week,
            120,
            600,
            true,
            false,
            2,
            monday(week).plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant()),
        "reopen-past-deadline");
    assertThrows(
        CommandConflict.class,
        () ->
            time.decide(
                "sophia",
                draft.id(),
                new Decision(BigDecimal.valueOf(2), "SUBMIT", "Late", key()),
                "late"));
    var updated =
        time.configurePeriod(
            "amelia", controls(week, 120, 600, true, false, 3, c.deadline()), "extend");
    assertEquals(4, updated.version());
    assertEquals(4, time.period("sophia", monday(week)).history().size());
    assertThrows(
        CommandConflict.class,
        () ->
            time.configurePeriod(
                "amelia", controls(week, 120, 600, true, false, 1, c.deadline()), "stale-config"));
    var again =
        time.decide(
            "sophia",
            draft.id(),
            new Decision(BigDecimal.valueOf(2), "SUBMIT", "Deadline extended", key()),
            "resubmit");
    var approved =
        time.decide(
            "marcus",
            draft.id(),
            new Decision(BigDecimal.valueOf(3), "APPROVE", "Checked period controls", key()),
            "approve");
    assertEquals(120, time.sheet("sophia", draft.id()).allocatedMinutes());
    assertThrows(
        CommandConflict.class,
        () ->
            time.decide(
                "sophia",
                draft.id(),
                new Decision(BigDecimal.valueOf(4), "RECALL", "Cannot recall approved", key()),
                "approved-recall"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("update time_period set snapshot=snapshot where week=?", monday(week)));
    assertThrows(
        org.springframework.dao.DataAccessException.class, () -> db.update("truncate time_period"));
  }

  @Test
  void clockAndCorrectionKeepHistoryReplayAndMissingPunchState() {
    var p = new Punch("Etc/UTC", key());
    var start = time.punch("sophia", "IN", p, "time-clock");
    assertNull(start.end());
    assertEquals(start, time.punch("sophia", "IN", p, "retry"));
    assertThrows(
        CommandConflict.class,
        () -> time.punch("sophia", "IN", new Punch("Etc/UTC", key()), "duplicate"));
    var end = time.punch("sophia", "OUT", new Punch("Etc/UTC", key()), "time-out");
    assertNotNull(end.end());
    assertEquals(1, end.version());
    var day = Instant.now().minusSeconds(172800).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var c =
        new Correction(
            null,
            BigDecimal.ZERO,
            day.toString(),
            day.plusSeconds(3600).toString(),
            "Etc/UTC",
            "Missed shift",
            key());
    var fixed = time.correct("sophia", c, "correction");
    assertEquals(fixed, time.correct("sophia", c, "retry"));
    assertThrows(
        CommandConflict.class,
        () ->
            time.correct(
                "sophia",
                new Correction(
                    null,
                    BigDecimal.ZERO,
                    day.plusSeconds(60).toString(),
                    day.plusSeconds(600).toString(),
                    "Etc/UTC",
                    "Overlap",
                    key()),
                "overlap"));
    assertThrows(AccessDenied.class, () -> time.attendance("outsider", "E003", 0, 10));
    assertTrue(time.attendance("marcus", "E003", 0, 10).total() >= 2);
    assertTrue(
        time.history("sophia", "E003", 0, 50).items().stream()
            .anyMatch(
                a -> a.action().equals("CORRECTED") && a.correlationId().equals("correction")));
  }

  @Test
  void draftReviewRejectionRevisionAndRealCostingReconcile() {
    var input = command(2, "7.25", 0);
    var sheet = time.save("sophia", input, "save");
    assertEquals(sheet, time.save("sophia", input, "replay"));
    assertEquals(435, sheet.totalMinutes());
    assertEquals(0, time.sheet("sophia", sheet.id()).allocatedMinutes());
    var submitted =
        time.decide(
            "sophia",
            sheet.id(),
            new Decision(BigDecimal.ZERO, "SUBMIT", "Please review", key()),
            "submit");
    assertThrows(
        AccessDenied.class,
        () ->
            time.decide(
                "sophia",
                sheet.id(),
                new Decision(BigDecimal.ONE, "APPROVE", "Self", key()),
                "denied"));
    assertThrows(AccessDenied.class, () -> time.sheet("outsider", sheet.id()));
    var rejected =
        time.decide(
            "marcus",
            sheet.id(),
            new Decision(BigDecimal.ONE, "REJECT", "Correct working hours", key()),
            "reject");
    assertEquals("REJECTED", rejected.status());
    assertEquals(0, time.sheet("marcus", sheet.id()).allocatedMinutes());
    var revised = time.save("sophia", command(2, "8", rejected.version()), "revise");
    var resubmitted =
        time.decide(
            "sophia",
            sheet.id(),
            new Decision(BigDecimal.valueOf(revised.version()), "SUBMIT", "Corrected", key()),
            "resubmit");
    var approval =
        new Decision(
            BigDecimal.valueOf(resubmitted.version()),
            "APPROVE",
            "Independent hours reviewed",
            key());
    var approved = time.decide("amelia", sheet.id(), approval, "approve");
    assertEquals(approved, time.decide("amelia", sheet.id(), approval, "repeat"));
    assertEquals(480, time.sheet("marcus", sheet.id()).allocatedMinutes());
    assertEquals(1, time.sheet("sophia", sheet.id()).allocations().size());
    assertThrows(
        CommandConflict.class,
        () -> time.save("sophia", command(2, "4", approved.version()), "edit-final"));
    assertEquals("SUBMITTED", submitted.status());
  }

  @Test
  void numericDateRoleAndTenantGuards() {
    var c = command(3, "1.001", 0);
    assertThrows(InputValidation.class, () -> time.save("sophia", c, "invalid"));
    assertThrows(
        InputValidation.class,
        () ->
            time.save(
                "sophia",
                new SaveSheet(
                    c.week(),
                    c.zone(),
                    new BigDecimal("0.5"),
                    command(3, "1", 0).entries(),
                    "fraction",
                    key()),
                "invalid"));
    assertThrows(
        InputValidation.class,
        () ->
            time.save(
                "sophia",
                new SaveSheet(
                    c.week().plusDays(1), c.zone(), BigDecimal.ZERO, c.entries(), "weekday", key()),
                "invalid"));
    var s = time.save("sophia", command(3, "1", 0), "save");
    assertEquals(0, time.sheets("outsider", true, "ALL", 0, 10).total());
    assertTrue(
        time.sheets("marcus", true, "ALL", 0, 50).items().stream()
            .anyMatch(x -> x.id().equals(s.id())));
    assertFalse(
        time.sheets("sophia", true, "ALL", 0, 50).items().stream()
            .anyMatch(x -> x.id().equals(s.id())));
    assertThrows(InputValidation.class, () -> time.sheets("sophia", false, "ALL", 0, 51));
    assertThrows(AccessDenied.class, () -> time.history("outsider", "E003", 0, 10));
  }

  @Test
  void concurrentApprovalsPostOnce() throws Exception {
    var s = time.save("sophia", command(4, "2", 0), "save");
    var submitted =
        time.decide(
            "sophia", s.id(), new Decision(BigDecimal.ZERO, "SUBMIT", "Ready", key()), "submit");
    var executor = Executors.newFixedThreadPool(2);
    try {
      var latch = new CountDownLatch(1);
      var tasks = new ArrayList<Future<Integer>>();
      for (var actor : List.of("amelia", "marcus"))
        tasks.add(
            executor.submit(
                () -> {
                  latch.await();
                  try {
                    time.decide(
                        actor,
                        s.id(),
                        new Decision(
                            BigDecimal.valueOf(submitted.version()),
                            "APPROVE",
                            "Independent review",
                            key()),
                        "race");
                    return 200;
                  } catch (CommandConflict e) {
                    return 409;
                  }
                }));
      latch.countDown();
      var outcomes = new ArrayList<Integer>();
      for (var f : tasks) outcomes.add(f.get(15, TimeUnit.SECONDS));
      Collections.sort(outcomes);
      assertEquals(List.of(200, 409), outcomes);
      assertEquals(120, time.sheet("sophia", s.id()).allocatedMinutes());
      assertEquals(
          1,
          db.queryForObject(
              "select count(*) from time_costing_entry where sheet_id=?", Integer.class, s.id()));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void subjectReassignmentCannotBypassIndependentReview() {
    var saved = time.save("sophia", command(6, "1", 0), "submitter");
    time.decide(
        "sophia",
        saved.id(),
        new Decision(BigDecimal.ZERO, "SUBMIT", "Please independently review", key()),
        "submit");
    db.update("update identity_membership set employee_id='E004',role='HR' where subject='sophia'");
    try {
      assertThrows(
          AccessDenied.class,
          () ->
              time.decide(
                  "sophia",
                  saved.id(),
                  new Decision(
                      BigDecimal.ONE,
                      "APPROVE",
                      "Former contributor now different employee",
                      key()),
                  "forbidden"));
    } finally {
      db.update(
          "update identity_membership set employee_id='E003',role='EMPLOYEE' where subject='sophia'");
    }
    assertEquals("SUBMITTED", time.sheet("sophia", saved.id()).sheet().status());
    assertEquals(0, time.sheet("sophia", saved.id()).allocatedMinutes());
  }

  @Test
  void completeOwnedPagesReachOlderAttendanceSheetsAndAudit() {
    var shiftIds = new HashSet<String>();
    var sheetIds = new HashSet<String>();
    for (int i = 0; i < 14; i++) {
      var start =
          LocalDate.now(ZoneOffset.UTC).minusDays(20 + i).atTime(9, 0).toInstant(ZoneOffset.UTC);
      shiftIds.add(
          time.correct(
                  "sophia",
                  new Correction(
                      null,
                      BigDecimal.ZERO,
                      start.toString(),
                      start.plusSeconds(3600).toString(),
                      "Etc/UTC",
                      "Paged attendance fixture",
                      key()),
                  "time-page")
              .id());
      sheetIds.add(time.save("sophia", command(20 + i, "1", 0), "time-page").id());
    }
    var actualShifts = new HashSet<String>();
    var actualSheets = new HashSet<String>();
    var auditIds = new HashSet<String>();
    for (int page = 0; ; page++) {
      var result = time.attendance("sophia", "E003", page, 5);
      assertEquals(5, result.size());
      result.items().forEach(row -> assertTrue(actualShifts.add(row.id())));
      if ((page + 1L) * 5 >= result.total()) {
        assertEquals(result.total(), actualShifts.size());
        break;
      }
    }
    for (int page = 0; ; page++) {
      var result = time.sheets("sophia", false, "ALL", page, 5);
      result.items().forEach(row -> assertTrue(actualSheets.add(row.id())));
      if ((page + 1L) * 5 >= result.total()) {
        assertEquals(result.total(), actualSheets.size());
        break;
      }
    }
    for (int page = 0; ; page++) {
      var result = time.history("sophia", "E003", page, 5);
      result.items().forEach(row -> assertTrue(auditIds.add(row.id())));
      if ((page + 1L) * 5 >= result.total()) {
        assertEquals(result.total(), auditIds.size());
        break;
      }
    }
    assertTrue(actualShifts.containsAll(shiftIds));
    assertTrue(actualSheets.containsAll(sheetIds));
    assertTrue(auditIds.size() >= 28);
  }

  @Test
  void replayCannotExposeFormerEmployeeRecordsAfterSubjectMove() {
    var punch = new Punch("Etc/UTC", key());
    time.punch("sophia", "IN", punch, "original-clock");
    time.punch("sophia", "OUT", new Punch("Etc/UTC", key()), "original-out");
    var start = LocalDate.now(ZoneOffset.UTC).minusDays(14).atTime(8, 0).toInstant(ZoneOffset.UTC);
    var correction =
        new Correction(
            null,
            BigDecimal.ZERO,
            start.toString(),
            start.plusSeconds(3600).toString(),
            "Etc/UTC",
            "Original employee record",
            key());
    time.correct("sophia", correction, "original-correction");
    var input = command(7, "1", 0);
    time.save("sophia", input, "original-sheet");
    db.update(
        "update identity_membership set employee_id='E004',role='EMPLOYEE' where subject='sophia'");
    try {
      assertThrows(AccessDenied.class, () -> time.punch("sophia", "IN", punch, "forbidden-replay"));
      assertThrows(
          AccessDenied.class, () -> time.correct("sophia", correction, "forbidden-replay"));
      assertThrows(AccessDenied.class, () -> time.save("sophia", input, "forbidden-replay"));
      assertEquals(0, time.sheets("sophia", false, "ALL", 0, 10).total());
      assertEquals(0, time.attendance("sophia", "E004", 0, 10).total());
    } finally {
      db.update(
          "update identity_membership set employee_id='E003',role='EMPLOYEE' where subject='sophia'");
    }
  }

  @Test
  void databaseRetainsHistoryAndRejectsMalformedOrUnbalancedWrites() {
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("truncate time_audit cascade"));

    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "insert into time_shift values('test','invalid','E003',current_date,now(),now()-interval '1 hour','Etc/UTC',0,'invalid',now())"));
    var s = time.save("sophia", command(5, "1", 0), "save");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("delete from time_audit"));
    time.decide(
        "sophia", s.id(), new Decision(BigDecimal.ZERO, "SUBMIT", "Review", key()), "submit");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "update time_sheet set status='APPROVED',version=2,snapshot=jsonb_set(jsonb_set(snapshot,'{status}',to_jsonb('APPROVED'::text)),'{version}','2') where id=?",
                s.id()));
    var future = time.save("sophia", command(-1, "0", 0), "future-empty-draft");
    var invalidFuture =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () ->
                db.update(
                    "update time_sheet set version=1,snapshot=snapshot||jsonb_build_object('version',1,'totalMinutes',60,'entries',jsonb_set(jsonb_set(snapshot->'entries','{0,minutes}','60'),'{0,code}',to_jsonb('DEMO'::text))) where id=?",
                    future.id()));
    assertTrue(
        invalidFuture.getMostSpecificCause().getMessage().contains("Invalid timesheet entry"));
    assertEquals("SUBMITTED", time.sheet("sophia", s.id()).sheet().status());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "insert into time_costing_entry values('atlas',?,current_date,60,'FAKE',now())",
                s.id()));
  }
}
