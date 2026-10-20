package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.finance.application.model.FinanceModels.*;
import com.atlas.modules.finance.application.port.in.FinanceUseCases;
import com.atlas.modules.payroll.application.model.PayrollModels;
import com.atlas.modules.payroll.application.port.in.PayrollUseCases;
import com.atlas.modules.timekeeping.application.model.TimeModels;
import com.atlas.modules.timekeeping.application.port.in.TimeUseCases;
import com.atlas.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PayrollAccountingWorkflowTest {
  static final String SCHEMA =
      "atlas_payfin_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://localhost:5438/atlas?currentSchema=" + SCHEMA);
    r.add("spring.flyway.default-schema", () -> SCHEMA);
    r.add("spring.flyway.schemas", () -> SCHEMA);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
  }

  @Autowired FinanceUseCases finance;
  @Autowired PayrollUseCases payroll;
  @Autowired TimeUseCases time;
  @Autowired PeopleUseCases people;
  @Autowired LegalEntitiesUseCases entities;
  @Autowired JdbcTemplate db;

  String key() {
    return UUID.randomUUID().toString();
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  LocalDate week() {
    return today().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  TimeModels.Sheet source(String user, int minutes) {
    var days = new ArrayList<TimeModels.EntryInput>();
    for (int i = 0; i < 7; i++) {
      var d = week().plusDays(i);
      days.add(
          new TimeModels.EntryInput(
              d,
              null,
              d.equals(today()) ? "SYNTHETIC-ENGINEERING" : "",
              BigDecimal.valueOf(d.equals(today()) ? minutes : 0)));
    }
    var s =
        time.save(
            user,
            new TimeModels.SaveSheet(
                week(), "Etc/UTC", BigDecimal.ZERO, days, "Synthetic financial test time", key()),
            "time-save");
    var draftSource = s;
    assertThrows(CommandConflict.class, () -> time.approvedSource(user, draftSource.id()));
    s =
        time.decide(
            user,
            s.id(),
            new TimeModels.Decision(BigDecimal.ZERO, "SUBMIT", "Actual time", key()),
            "time-submit");
    return time.decide(
        user.equals("sophia") ? "marcus" : "amelia",
        s.id(),
        new TimeModels.Decision(BigDecimal.ONE, "APPROVE", "Independent time review", key()),
        "time-approve");
  }

  @BeforeAll
  void setup() {
    var a = people.actor("amelia");
    entities.create(
        a,
        new LegalEntityModels.Create(
            new LegalEntityModels.Input(
                "DEMO-FIN", "Synthetic financial fixture", "US", "USD", "Etc/UTC", today()),
            "Demonstration reference only",
            key()),
        "entity-create");
    for (var employee : List.of("E003", "E002")) {
      var r =
          people.submit(
              a,
              new Submit(
                  employee,
                  "legalEntityCode",
                  "DEMO-FIN",
                  today(),
                  "Synthetic current entity assignment",
                  people.profile(a, employee).version(),
                  key()),
              "entity-submit");
      people.decide(
          people.actor("olivia"),
          r.id(),
          new Decide("APPROVED", "Independent review"),
          "entity-review");
    }
    finance.setup(
        "amelia",
        new Setup("DEMO-FIN", List.of("ENGINEERING"), "Synthetic USD chart", key()),
        "setup-ledger");
  }

  PayrollModels.Run result;

  @Test
  @Order(1)
  void approvedTimeToFinalizedPayslipAndBalancedPostedJournal() {
    var source = source("sophia", 61);
    var c =
        new PayrollModels.Prepare(
            null,
            BigDecimal.ZERO,
            "DEMO-FIN",
            source.id(),
            "ENGINEERING",
            today(),
            new BigDecimal("20.125"),
            new BigDecimal("0.46"),
            "Synthetic approved minutes",
            key());
    var r = payroll.prepare("amelia", c, "calculate");
    assertEquals(new BigDecimal("20.46"), r.gross());
    assertEquals(new BigDecimal("20.00"), r.net());
    assertEquals(r, payroll.prepare("amelia", c, "retry-calculate"));
    assertThrows(AccessDenied.class, () -> payroll.run("sophia", r.id()));
    assertThrows(AccessDenied.class, () -> payroll.run("outsider", r.id()));
    var submitted =
        payroll.action(
            "amelia",
            r.id(),
            new PayrollModels.Action(BigDecimal.ZERO, "SUBMIT", "Reviewed fixture", key()),
            "submit-run");
    assertThrows(
        AccessDenied.class,
        () ->
            payroll.action(
                "amelia",
                r.id(),
                new PayrollModels.Action(BigDecimal.ONE, "FINALIZE", "Self review denied", key()),
                "forbidden-final"));
    var finalized =
        payroll.action(
            "olivia",
            r.id(),
            new PayrollModels.Action(
                BigDecimal.valueOf(submitted.version()),
                "FINALIZE",
                "Independent fixture review",
                key()),
            "finalize");
    assertEquals("NOT_CONFIGURED", finalized.paymentStatus());
    assertTrue(payroll.payslip("sophia", r.id(), "payslip").content().contains("Net 20.00"));
    assertThrows(AccessDenied.class, () -> payroll.payslip("marcus", r.id(), "forbidden-payslip"));
    var command =
        new PayrollModels.Action(
            BigDecimal.valueOf(finalized.version()),
            "CREATE_JOURNAL",
            "Reviewed ledger mapping",
            key());
    var linked = payroll.action("amelia", r.id(), command, "accounting-draft");
    assertEquals(linked, payroll.action("amelia", r.id(), command, "accounting-retry"));
    var journal = finance.journal("marcus", linked.journalId());
    assertEquals("DRAFT", journal.status());
    assertEquals(r.id(), journal.sourceId());
    assertEquals(new BigDecimal("20.46"), journal.lines().getFirst().debit());
    journal =
        finance.action(
            "amelia",
            journal.id(),
            new Action(BigDecimal.ZERO, "SUBMIT", "Review source totals", key()),
            "journal-submit");
    journal =
        finance.action(
            "olivia",
            journal.id(),
            new Action(
                BigDecimal.valueOf(journal.version()),
                "APPROVE",
                "Independent mapping review",
                key()),
            "journal-approve");
    journal =
        finance.action(
            "marcus",
            journal.id(),
            new Action(
                BigDecimal.valueOf(journal.version()), "POST", "Reconciled payroll source", key()),
            "journal-post");
    assertEquals("POSTED", journal.status());
    var balance = finance.trialBalance("marcus", "DEMO-FIN", today(), today());
    assertEquals(new BigDecimal("20.46"), balance.debit());
    assertEquals(balance.debit(), balance.credit());
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from fin_journal where source_type='PAYROLL' and source_id=?",
            Integer.class,
            r.id()));
    assertEquals(1, payroll.runs("sophia", "", 0).total());
    result = linked;
  }

  List<Line> lines(String value) {
    return List.of(
        new Line(
            "6000", "ENGINEERING", new BigDecimal(value), BigDecimal.ZERO, "Synthetic expense"),
        new Line("1000", "", BigDecimal.ZERO, new BigDecimal(value), "Synthetic offset"));
  }

  Journal draft(String value) {
    return finance.save(
        "amelia",
        new SaveJournal(
            null,
            "DEMO-FIN",
            today(),
            BigDecimal.ZERO,
            lines(value),
            "Synthetic manual entry",
            key()),
        "manual-save");
  }

  Journal approve(Journal j) {
    j =
        finance.action(
            "amelia",
            j.id(),
            new Action(BigDecimal.valueOf(j.version()), "SUBMIT", "Review", key()),
            "manual-submit");
    return finance.action(
        "olivia",
        j.id(),
        new Action(BigDecimal.valueOf(j.version()), "APPROVE", "Independent review", key()),
        "manual-approve");
  }

  @Test
  @Order(2)
  void manualJournalPostsAndReversalPreservesOriginalAndOffsetsBalances() {
    var original = approve(draft("100.00"));
    original =
        finance.action(
            "marcus",
            original.id(),
            new Action(BigDecimal.valueOf(original.version()), "POST", "Authorised posting", key()),
            "manual-post");
    var reversal =
        finance.action(
            "amelia",
            original.id(),
            new Action(
                BigDecimal.valueOf(original.version()), "REVERSE", "Correct manual fixture", key()),
            "reverse-prepare");
    assertEquals("POSTED", finance.journal("amelia", original.id()).status());
    reversal = approve(reversal);
    finance.action(
        "marcus",
        reversal.id(),
        new Action(BigDecimal.valueOf(reversal.version()), "POST", "Approved reversal", key()),
        "reverse-post");
    assertEquals("REVERSED", finance.journal("amelia", original.id()).status());
    var b = finance.trialBalance("marcus", "DEMO-FIN", today(), today());
    assertEquals(b.debit(), b.credit());
    var expense =
        b.items().stream().filter(x -> x.account().equals("6000")).findFirst().orElseThrow();
    assertEquals(new BigDecimal("20.46"), expense.debit().subtract(expense.credit()));
  }

  @Test
  @Order(3)
  void blockingPayrollExceptionsRequireCorrectionAndReapproval() {
    var s = source("marcus", 60);
    var r =
        payroll.prepare(
            "amelia",
            new PayrollModels.Prepare(
                null,
                BigDecimal.ZERO,
                "DEMO-FIN",
                s.id(),
                "ENGINEERING",
                today(),
                new BigDecimal("10.00"),
                new BigDecimal("20.00"),
                "Deliberate blocking test",
                key()),
            "blocked-payroll");
    assertFalse(r.exceptions().isEmpty());
    var invalid = r;
    assertThrows(
        CommandConflict.class,
        () ->
            payroll.action(
                "amelia",
                invalid.id(),
                new PayrollModels.Action(BigDecimal.ZERO, "SUBMIT", "Must block", key()),
                "blocked-submit"));
    r =
        payroll.prepare(
            "amelia",
            new PayrollModels.Prepare(
                r.id(),
                BigDecimal.valueOf(r.version()),
                r.entity(),
                s.id(),
                r.costCentre(),
                today(),
                new BigDecimal("30.00"),
                BigDecimal.ZERO,
                "Corrected voluntary deduction",
                key()),
            "correct-payroll");
    assertTrue(r.exceptions().isEmpty());
    r =
        payroll.action(
            "amelia",
            r.id(),
            new PayrollModels.Action(
                BigDecimal.valueOf(r.version()), "SUBMIT", "Corrected inputs", key()),
            "resubmit");
    r =
        payroll.action(
            "olivia",
            r.id(),
            new PayrollModels.Action(
                BigDecimal.valueOf(r.version()), "RETURN", "Check entered hourly rate", key()),
            "return-payroll");
    assertEquals("RETURNED", r.status());
  }

  @Test
  @Order(4)
  void concurrentPostRetriesHaveOneDocumentAndOnePostingAudit() throws Exception {
    var j = approve(draft("42.00"));
    var c = new Action(BigDecimal.valueOf(j.version()), "POST", "Post once", key());
    var gate = new CountDownLatch(1);
    try (var exec = Executors.newFixedThreadPool(2)) {
      var first =
          exec.submit(
              () -> {
                gate.await();
                return finance.action("marcus", j.id(), c, "post-race");
              });
      var second =
          exec.submit(
              () -> {
                gate.await();
                return finance.action("marcus", j.id(), c, "post-race-retry");
              });
      gate.countDown();
      assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from fin_audit where document_id=? and action='POST'",
            Integer.class,
            j.id()));
  }

  @Test
  @Order(5)
  void periodsPermissionsAndDatabaseGuardsPreventInvalidMutations() {
    var j = approve(draft("7.00"));
    assertThrows(AccessDenied.class, () -> finance.journal("sophia", j.id()));
    assertThrows(AccessDenied.class, () -> finance.journal("outsider", j.id()));
    assertThrows(
        AccessDenied.class,
        () ->
            finance.save(
                "olivia",
                new SaveJournal(
                    null,
                    "DEMO-FIN",
                    today(),
                    BigDecimal.ZERO,
                    lines("1.00"),
                    "No prepare grant",
                    key()),
                "forbidden"));
    var closed =
        finance.setPeriod(
            "amelia",
            new SetPeriod(
                "DEMO-FIN",
                today().withDayOfMonth(1),
                true,
                BigDecimal.ZERO,
                "Close fixture month",
                key()),
            "close-period");
    try {
      assertThrows(
          CommandConflict.class,
          () ->
              finance.action(
                  "marcus",
                  j.id(),
                  new Action(
                      BigDecimal.valueOf(j.version()), "POST", "Closed period denied", key()),
                  "closed-post"));
      assertThrows(CommandConflict.class, () -> draft("1.00"));
    } finally {
      finance.setPeriod(
          "amelia",
          new SetPeriod(
              "DEMO-FIN",
              closed.month(),
              false,
              BigDecimal.valueOf(closed.version()),
              "Reopen fixture month",
              key()),
          "reopen-period");
    }
    var posted =
        finance.action(
            "marcus",
            j.id(),
            new Action(BigDecimal.valueOf(j.version()), "POST", "Reopened period", key()),
            "post-after-open");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "update fin_journal set snapshot=jsonb_set(snapshot,'{lines,0,debit}','999'),version=version+1 where id=?",
                posted.id()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("delete from fin_journal where id=?", posted.id()));
    assertThrows(
        org.springframework.dao.DataAccessException.class, () -> db.update("truncate pay_run"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("update fin_audit set reason=reason"));
    assertEquals(
        new BigDecimal("1.00"),
        db.queryForObject("select pay_round_even(1.005)", BigDecimal.class).setScale(2));
  }

  @Test
  @Order(6)
  void payrollAccountingFailureRollsBackAndCanRetryWithoutDuplicateJournal() {
    var returned =
        payroll.runs("amelia", "DEMO-FIN", 0).items().stream()
            .filter(r -> r.employeeId().equals("E002"))
            .findFirst()
            .orElseThrow();
    var corrected =
        payroll.prepare(
            "amelia",
            new PayrollModels.Prepare(
                returned.id(),
                BigDecimal.valueOf(returned.version()),
                returned.entity(),
                returned.source().id(),
                returned.costCentre(),
                today(),
                new BigDecimal("30.00"),
                BigDecimal.ZERO,
                "Verified returned input",
                key()),
            "verify-returned");
    var submitted =
        payroll.action(
            "amelia",
            corrected.id(),
            new PayrollModels.Action(
                BigDecimal.valueOf(corrected.version()),
                "SUBMIT",
                "Reapprove corrected input",
                key()),
            "resubmit-correction");
    var finalized =
        payroll.action(
            "olivia",
            submitted.id(),
            new PayrollModels.Action(
                BigDecimal.valueOf(submitted.version()),
                "FINALIZE",
                "Independent correction review",
                key()),
            "finalize-correction");
    var prior = finance.period("amelia", "DEMO-FIN", today().withDayOfMonth(1));
    var closed =
        finance.setPeriod(
            "amelia",
            new SetPeriod(
                "DEMO-FIN",
                today().withDayOfMonth(1),
                true,
                BigDecimal.valueOf(prior.version()),
                "Deliberate integration rejection",
                key()),
            "integration-close");
    var command =
        new PayrollModels.Action(
            BigDecimal.valueOf(finalized.version()),
            "CREATE_JOURNAL",
            "Retry after period review",
            key());
    try {
      assertThrows(
          CommandConflict.class,
          () -> payroll.action("amelia", finalized.id(), command, "rejected-posting"));
      assertNull(payroll.run("amelia", finalized.id()).journalId());
      assertEquals(
          0,
          db.queryForObject(
              "select count(*) from fin_journal where source_type='PAYROLL' and source_id=?",
              Integer.class,
              finalized.id()));
    } finally {
      finance.setPeriod(
          "amelia",
          new SetPeriod(
              "DEMO-FIN",
              closed.month(),
              false,
              BigDecimal.valueOf(closed.version()),
              "Authorised recovery",
              key()),
          "integration-reopen");
    }
    var linked = payroll.action("amelia", finalized.id(), command, "retry-posting");
    assertEquals(linked, payroll.action("amelia", finalized.id(), command, "replay-posting"));
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from fin_journal where source_type='PAYROLL' and source_id=?",
            Integer.class,
            finalized.id()));
    assertThrows(
        CommandConflict.class,
        () ->
            payroll.prepare(
                "amelia",
                new PayrollModels.Prepare(
                    null,
                    BigDecimal.ZERO,
                    finalized.entity(),
                    finalized.source().id(),
                    finalized.costCentre(),
                    today(),
                    new BigDecimal("30.00"),
                    BigDecimal.ZERO,
                    "Duplicate source denied",
                    key()),
                "duplicate-source"));
  }

  @Test
  @Order(7)
  void financialValidationAndGrantedSelfApprovalAreDeniedWithoutMutation() {
    var badAccount =
        List.of(
            new Line("9999", "ENGINEERING", BigDecimal.ONE, BigDecimal.ZERO, "Invalid account"),
            new Line("1000", "", BigDecimal.ZERO, BigDecimal.ONE, "Offset"));
    assertThrows(
        InputValidation.class,
        () ->
            finance.save(
                "amelia",
                new SaveJournal(
                    null,
                    "DEMO-FIN",
                    today(),
                    BigDecimal.ZERO,
                    badAccount,
                    "Invalid account denied",
                    key()),
                "invalid-account"));
    var badDimension =
        List.of(
            new Line("6000", "UNASSIGNED", BigDecimal.ONE, BigDecimal.ZERO, "Invalid dimension"),
            new Line("1000", "", BigDecimal.ZERO, BigDecimal.ONE, "Offset"));
    assertThrows(
        InputValidation.class,
        () ->
            finance.save(
                "amelia",
                new SaveJournal(
                    null,
                    "DEMO-FIN",
                    today(),
                    BigDecimal.ZERO,
                    badDimension,
                    "Invalid dimension denied",
                    key()),
                "invalid-dimension"));
    var unbalanced =
        List.of(
            new Line("6000", "ENGINEERING", new BigDecimal("2.00"), BigDecimal.ZERO, "Unbalanced"),
            new Line("1000", "", BigDecimal.ZERO, BigDecimal.ONE, "Offset"));
    assertThrows(
        InputValidation.class,
        () ->
            finance.save(
                "amelia",
                new SaveJournal(
                    null,
                    "DEMO-FIN",
                    today(),
                    BigDecimal.ZERO,
                    unbalanced,
                    "Unbalanced denied",
                    key()),
                "unbalanced"));
    var command =
        new SaveJournal(
            null, "DEMO-FIN", today(), BigDecimal.ZERO, lines("2.00"), "Receipt fixture", key());
    var saved = finance.save("amelia", command, "receipt-first");
    assertEquals(saved, finance.save("amelia", command, "receipt-replay"));
    assertThrows(
        CommandConflict.class,
        () ->
            finance.save(
                "amelia",
                new SaveJournal(
                    null,
                    "DEMO-FIN",
                    today(),
                    BigDecimal.ZERO,
                    lines("3.00"),
                    "Receipt fixture",
                    command.idempotencyKey()),
                "receipt-conflict"));
    var pending =
        finance.action(
            "amelia",
            saved.id(),
            new Action(BigDecimal.ZERO, "SUBMIT", "Review", key()),
            "self-review-source");
    db.update("insert into fin_grant values('atlas','amelia','E001','DEMO-FIN','APPROVE')");
    assertThrows(
        AccessDenied.class,
        () ->
            finance.action(
                "amelia",
                pending.id(),
                new Action(
                    BigDecimal.valueOf(pending.version()),
                    "APPROVE",
                    "Granted self-review still denied",
                    key()),
                "forbidden-independent"));
    db.update("insert into fin_grant values('atlas','amelia','E001','DEMO-FIN','PAYROLL_APPROVE')");
    assertThrows(
        AccessDenied.class,
        () ->
            payroll.action(
                "amelia",
                result.id(),
                new PayrollModels.Action(
                    BigDecimal.valueOf(result.version()),
                    "FINALIZE",
                    "Granted self-finalization still denied",
                    key()),
                "payroll-forbidden-independent"));
    var forged = key();
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "insert into fin_journal(tenant,id,entity,posting_date,source_type,source_id,status,version,snapshot) select tenant,?,entity,posting_date,'MANUAL',?,'DRAFT',0,jsonb_set(jsonb_set(jsonb_set(jsonb_set(jsonb_set(snapshot,'{id}',to_jsonb(?::text)),'{sourceId}',to_jsonb(?::text)),'{status}','\"DRAFT\"'),'{version}','0'),'{lines,0,debit}','999') from fin_journal where id=?",
                forged,
                forged,
                forged,
                forged,
                pending.id()));
  }
}
