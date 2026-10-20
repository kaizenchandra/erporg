package com.atlas.modules.payroll.domain.model;

import com.atlas.shared.domain.CommandConflict;
import java.util.Set;

/** Finalization, accounting creation and payment are deliberately separate concepts. */
public final class PayrollWorkflow {
  private PayrollWorkflow() {}

  public static String transition(
      String status, String action, boolean blockingExceptions, boolean hasJournal) {
    return switch (action) {
      case "SUBMIT" ->
          allowed(
              Set.of("CALCULATED", "RETURNED").contains(status) && !blockingExceptions,
              "SUBMITTED");
      case "RETURN" -> allowed(status.equals("SUBMITTED"), "RETURNED");
      case "FINALIZE" -> allowed(status.equals("SUBMITTED") && !blockingExceptions, "FINALIZED");
      case "CREATE_JOURNAL" -> allowed(status.equals("FINALIZED") && !hasJournal, "FINALIZED");
      default -> throw new CommandConflict("Unknown payroll transition.");
    };
  }

  private static String allowed(boolean valid, String next) {
    if (!valid)
      throw new CommandConflict(
          "Payroll state or blocking exceptions prevent this action; correct inputs or reload the current result.");
    return next;
  }
}
