package com.atlas.modules.finance.domain.model;

import com.atlas.shared.domain.CommandConflict;
import java.util.Set;

/**
 * Pure financial state transitions. Identity and configured action grants are application policy.
 */
public final class JournalWorkflow {
  private JournalWorkflow() {}

  public static String transition(String status, String action, String sourceType) {
    return switch (action) {
      case "SUBMIT" ->
          allowed(Set.of("DRAFT", "RETURNED", "REJECTED").contains(status), "SUBMITTED");
      case "APPROVE" -> allowed(status.equals("SUBMITTED"), "APPROVED");
      case "RETURN" -> allowed(status.equals("SUBMITTED"), "RETURNED");
      case "REJECT" -> allowed(status.equals("SUBMITTED"), "REJECTED");
      case "POST" -> allowed(status.equals("APPROVED"), "POSTED");
      case "REVERSE" -> {
        if (!sourceType.equals("MANUAL"))
          throw new CommandConflict(
              "Only original manual journals may be reversed here; corrections to payroll or reversal sources require their governed workflow.");
        yield allowed(status.equals("POSTED"), "DRAFT");
      }
      default -> throw new CommandConflict("Unknown journal transition.");
    };
  }

  public static String counterEntryPosted(String originalStatus) {
    return allowed(originalStatus.equals("POSTED"), "REVERSED");
  }

  private static String allowed(boolean valid, String next) {
    if (!valid)
      throw new CommandConflict(
          "Journal state does not permit this action; reload its current history.");
    return next;
  }
}
