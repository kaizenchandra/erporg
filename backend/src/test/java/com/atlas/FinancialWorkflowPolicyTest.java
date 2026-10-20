package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.finance.domain.model.JournalWorkflow;
import com.atlas.modules.payroll.domain.model.PayrollWorkflow;
import com.atlas.shared.domain.CommandConflict;
import org.junit.jupiter.api.Test;

class FinancialWorkflowPolicyTest {
  @Test
  void journalTransitionsNeverEditPostedHistoryOrReverseAnUnsupportedSource() {
    assertEquals("SUBMITTED", JournalWorkflow.transition("DRAFT", "SUBMIT", "MANUAL"));
    assertEquals("POSTED", JournalWorkflow.transition("APPROVED", "POST", "MANUAL"));
    assertEquals("DRAFT", JournalWorkflow.transition("POSTED", "REVERSE", "MANUAL"));
    assertEquals("REVERSED", JournalWorkflow.counterEntryPosted("POSTED"));
    assertThrows(
        CommandConflict.class, () -> JournalWorkflow.transition("POSTED", "SUBMIT", "MANUAL"));
    assertThrows(
        CommandConflict.class, () -> JournalWorkflow.transition("DRAFT", "POST", "MANUAL"));
    assertThrows(
        CommandConflict.class, () -> JournalWorkflow.transition("POSTED", "REVERSE", "PAYROLL"));
    assertThrows(
        CommandConflict.class, () -> JournalWorkflow.transition("POSTED", "REVERSE", "REVERSAL"));
  }

  @Test
  void payrollBlockingRulesAndAccountingTransitionsAreIndependentOfPayment() {
    assertEquals("SUBMITTED", PayrollWorkflow.transition("CALCULATED", "SUBMIT", false, false));
    assertEquals("RETURNED", PayrollWorkflow.transition("SUBMITTED", "RETURN", true, false));
    assertEquals("FINALIZED", PayrollWorkflow.transition("SUBMITTED", "FINALIZE", false, false));
    assertEquals(
        "FINALIZED", PayrollWorkflow.transition("FINALIZED", "CREATE_JOURNAL", false, false));
    assertThrows(
        CommandConflict.class,
        () -> PayrollWorkflow.transition("CALCULATED", "SUBMIT", true, false));
    assertThrows(
        CommandConflict.class,
        () -> PayrollWorkflow.transition("FINALIZED", "CREATE_JOURNAL", false, true));
    assertThrows(
        CommandConflict.class,
        () -> PayrollWorkflow.transition("FINALIZED", "RETURN", false, false));
    assertThrows(
        CommandConflict.class, () -> PayrollWorkflow.transition("FINALIZED", "PAY", false, false));
  }
}
