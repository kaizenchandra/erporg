package com.atlas.modules.timekeeping.application.usecase;

import com.atlas.modules.timekeeping.application.model.TimeModels.Actor;
import com.atlas.modules.timekeeping.domain.model.TimePolicy;
import com.atlas.shared.domain.CommandConflict;
import java.util.Set;

/** Module-local command context checks shared by attendance, sheets and period controls. */
final class TimeCommandRules {
  private TimeCommandRules() {}

  static String key(String key) {
    TimePolicy.require(
        key != null && key.matches("[a-zA-Z0-9-]{8,80}"),
        "idempotencyKey",
        "Use a valid command key.");
    return key;
  }

  static void active(Actor a) {
    TimePolicy.require(
        Set.of("Active", "On leave").contains(a.status()),
        "employee",
        "An active employment episode is required.");
  }

  static void sameIdentity(Actor before, Actor after) {
    if (!before.tenant().equals(after.tenant())
        || !before.employeeId().equals(after.employeeId())
        || !before.start().equals(after.start()))
      throw new CommandConflict(
          "Identity or employment context changed while waiting; reload before retrying.");
  }
}
