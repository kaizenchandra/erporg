package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.talent.domain.model.TalentModels.*;
import com.atlas.modules.talent.domain.model.TalentPolicy;
import com.atlas.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TalentPolicyTest {
  @Test
  void budgetUsesOnlySalaryIncreasePlusBonusWithExactCents() {
    var p =
        new Proposal(
            "E1",
            new BigDecimal("10000.00"),
            new BigDecimal("11000.00"),
            new BigDecimal("500.25"),
            LocalDate.of(2026, 10, 21));
    assertEquals(
        new BigDecimal("1500.25"),
        TalentPolicy.compensation(List.of(p), new BigDecimal("1500.25")));
    assertThrows(
        InputValidation.class,
        () -> TalentPolicy.compensation(List.of(p), new BigDecimal("1500.24")));
    assertThrows(
        InputValidation.class,
        () -> TalentPolicy.compensation(List.of(p, p), new BigDecimal("9999.00")));
  }

  @Test
  void candidateStagesAndDependentTasksCannotSkipForward() {
    assertThrows(InputValidation.class, () -> TalentPolicy.candidate("APPLIED", "ACCEPTED"));
    var first = new Task("one", "Provision", "E1", List.of(), false, "", null, "");
    var second = new Task("two", "Orient", "E1", List.of("one"), false, "", null, "");
    assertThrows(
        InputValidation.class, () -> TalentPolicy.dependencies(List.of(first, second), second));
    assertThrows(CommandConflict.class, () -> TalentPolicy.requisition("CLOSED", "SUBMIT"));
  }

  @Test
  void exactMoneyAndScenarioHaveNoBinaryFloatingRounding() {
    assertEquals(new BigDecimal("0.10"), TalentPolicy.money("0.10", "cost"));
    assertThrows(InputValidation.class, () -> TalentPolicy.money("0.001", "cost"));
    assertThrows(InputValidation.class, () -> TalentPolicy.money("1000000000000", "cost"));
    var meta = new Stamp("fixture", 0, "SAVED", "one", "E1", Instant.EPOCH);
    var s =
        TalentPolicy.scenario(
            meta,
            "Fixture",
            "USD",
            LocalDate.of(2026, 10, 21),
            List.of("E1", "E2"),
            List.of(new CostLine("Engineering", 2, 3, new BigDecimal("12345.67"))));
    assertEquals(new BigDecimal("24691.34"), s.baselineCost());
    assertEquals(new BigDecimal("37037.01"), s.proposedCost());
    assertEquals(new BigDecimal("12345.67"), s.variance());
  }

  @Test
  void employeeAliasesCannotApproveOwnPlan() {
    var stamp = new Stamp("fixture", 0, "SUBMITTED", "one", "E1", Instant.EPOCH);
    assertThrows(AccessDenied.class, () -> TalentPolicy.independent(stamp, "one-alias", "E1"));
    assertThrows(AccessDenied.class, () -> TalentPolicy.independent(stamp, "one", "E2"));
  }
}
