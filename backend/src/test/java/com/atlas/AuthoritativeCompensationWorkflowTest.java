package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.finance.application.model.FinanceModels.*;
import com.atlas.modules.finance.application.port.in.FinanceUseCases;
import com.atlas.modules.payroll.application.model.CompensationModels;
import com.atlas.modules.payroll.application.model.PayrollModels;
import com.atlas.modules.payroll.application.port.in.*;
import com.atlas.modules.talent.application.model.TalentCommands;
import com.atlas.modules.talent.application.port.in.TalentUseCases;
import com.atlas.modules.timekeeping.application.model.TimeModels;
import com.atlas.modules.timekeeping.application.port.in.TimeUseCases;
import com.atlas.shared.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;
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
class AuthoritativeCompensationWorkflowTest {
  static final String SCHEMA =
      "atlas_compensation_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://localhost:5438/atlas?currentSchema=" + SCHEMA);
    r.add("spring.flyway.default-schema", () -> SCHEMA);
    r.add("spring.flyway.schemas", () -> SCHEMA);
  }

  @Autowired FinanceUseCases finance;
  @Autowired PayrollUseCases payroll;
  @Autowired TimeUseCases time;
  @Autowired PeopleUseCases people;
  @Autowired LegalEntitiesUseCases entities;
  @Autowired JdbcTemplate db;
  @Autowired CompensationUseCases compensation;
  @Autowired TalentUseCases talent;

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

  @Test
  void futureSalaryAndBonusCannotEnterAnEarlierPayrollBoundary() {
    var baseline =
        compensation.propose(
            "amelia",
            new CompensationModels.Baseline(
                "E002",
                "USD",
                new BigDecimal("100000.00"),
                new BigDecimal("2000.00"),
                today().minusDays(30),
                "Approved current baseline",
                key()),
            "baseline");
    compensation.decide(
        "olivia",
        baseline.id(),
        new CompensationModels.Decision("APPROVE", "Independent review", key()),
        "approve");
    var plan =
        talent.compensation(
            "amelia",
            new TalentCommands.CompensationInput(
                "Future award",
                "USD",
                "10500.25",
                List.of(
                    new TalentCommands.ProposalInput(
                        "E002", null, "110000.00", "500.25", today().plusDays(1))),
                "Future effective award",
                key()),
            "future");
    talent.compensationAction(
        "amelia",
        plan.meta().id(),
        new TalentCommands.Decision(BigDecimal.ZERO, "SUBMIT", "Review future award", key()),
        "submit");
    talent.compensationAction(
        "olivia",
        plan.meta().id(),
        new TalentCommands.Decision(BigDecimal.ONE, "APPROVE", "Independent review", key()),
        "approve");
    var source = source("marcus", 61);
    var r =
        payroll.prepareCompensation(
            "amelia",
            new PayrollModels.Prepare(
                null,
                BigDecimal.ZERO,
                "DEMO-FIN",
                source.id(),
                "ENGINEERING",
                today(),
                null,
                BigDecimal.ZERO,
                "Before future award date",
                key()),
            "calculate");
    assertEquals(new BigDecimal("50.83"), r.gross());
    var c = compensation.calculation("amelia", r.id());
    assertEquals(new BigDecimal("0.00"), c.bonus());
    assertTrue(c.bonuses().isEmpty());
    assertEquals(new BigDecimal("100000.00"), c.days().getFirst().salary().annualSalary());
  }

  @Test
  void approvedTalentSalaryAndBonusReachRealPayrollPayslipAndBalancedFinanceExactlyOnce() {
    var input =
        new CompensationModels.Baseline(
            "E003",
            "USD",
            new BigDecimal("100000.00"),
            new BigDecimal("2000.00"),
            today().minusDays(30),
            "Independently established fixture; not statutory",
            key());
    assertThrows(AccessDenied.class, () -> compensation.propose("sophia", input, "denied"));
    var baseline = compensation.propose("amelia", input, "baseline");
    assertEquals(baseline, compensation.propose("amelia", input, "replay"));
    var approve =
        new CompensationModels.Decision("APPROVE", "Independent source verification", key());
    assertThrows(
        AccessDenied.class, () -> compensation.decide("amelia", baseline.id(), approve, "self"));
    compensation.decide("olivia", baseline.id(), approve, "approve");
    assertEquals(
        new BigDecimal("100000.00"), compensation.salary("amelia", "E003", today()).annualSalary());
    var wrong =
        new TalentCommands.CompensationInput(
            "Wrong salary",
            "USD",
            "10500.25",
            List.of(
                new TalentCommands.ProposalInput(
                    "E003", "99999.00", "110000.00", "500.25", today())),
            "Synthetic stale fixture",
            key());
    assertThrows(InputValidation.class, () -> talent.compensation("amelia", wrong, "stale"));
    var plan =
        talent.compensation(
            "amelia",
            new TalentCommands.CompensationInput(
                "Approved salary and bonus",
                "USD",
                "10500.25",
                List.of(
                    new TalentCommands.ProposalInput("E003", null, "110000.00", "500.25", today())),
                "Synthetic plan",
                key()),
            "plan");
    assertEquals(new BigDecimal("100000.00"), plan.proposals().getFirst().currentSalary());
    talent.compensationAction(
        "amelia",
        plan.meta().id(),
        new TalentCommands.Decision(BigDecimal.ZERO, "SUBMIT", "Reviewed proposal", key()),
        "submit");
    var approvePlan =
        new TalentCommands.Decision(BigDecimal.ONE, "APPROVE", "Independent review", key());
    var applied = talent.compensationAction("olivia", plan.meta().id(), approvePlan, "approve");
    assertEquals(
        applied, talent.compensationAction("olivia", plan.meta().id(), approvePlan, "replay"));
    assertEquals(
        new BigDecimal("110000.00"), compensation.salary("amelia", "E003", today()).annualSalary());
    assertEquals(2, compensation.state("amelia", "E003").salaries().size());
    var source = source("sophia", 61);
    var command =
        new PayrollModels.Prepare(
            null,
            BigDecimal.ZERO,
            "DEMO-FIN",
            source.id(),
            "ENGINEERING",
            today(),
            null,
            new BigDecimal("0.46"),
            "Consume authoritative approved compensation",
            key());
    var run = payroll.prepareCompensation("amelia", command, "calculate");
    assertEquals(new BigDecimal("556.17"), run.gross());
    assertEquals(new BigDecimal("555.71"), run.net());
    assertEquals(run, payroll.prepareCompensation("amelia", command, "replay"));
    var snapshot = compensation.calculation("amelia", run.id());
    assertEquals(new BigDecimal("55.92"), snapshot.baseGross());
    assertEquals(new BigDecimal("500.25"), snapshot.bonus());
    assertEquals(1, snapshot.bonuses().size());
    assertThrows(AccessDenied.class, () -> compensation.calculation("sophia", run.id()));
    assertThrows(AccessDenied.class, () -> compensation.calculation("outsider", run.id()));
    assertEquals(1, db.queryForObject("select count(*) from pay_comp_bonus_use", Integer.class));
    payroll.action(
        "amelia",
        run.id(),
        new PayrollModels.Action(BigDecimal.ZERO, "SUBMIT", "Reviewed result", key()),
        "submit");
    payroll.action(
        "olivia",
        run.id(),
        new PayrollModels.Action(BigDecimal.ONE, "FINALIZE", "Independent approval", key()),
        "finalize");
    assertEquals(snapshot, compensation.calculation("sophia", run.id()));
    assertTrue(payroll.payslip("sophia", run.id(), "read").content().contains("Net 555.71"));
    var linked =
        payroll.action(
            "amelia",
            run.id(),
            new PayrollModels.Action(
                new BigDecimal("2"), "CREATE_JOURNAL", "Reconcile compensation payroll", key()),
            "finance");
    var journal = finance.journal("marcus", linked.journalId());
    assertEquals(run.gross(), journal.lines().getFirst().debit());
    var debit =
        journal.lines().stream().map(l -> l.debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
    var credit =
        journal.lines().stream().map(l -> l.credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(debit, credit);
    assertEquals("NOT_CONFIGURED", linked.paymentStatus());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("delete from pay_comp_bonus_use"));
  }
}
