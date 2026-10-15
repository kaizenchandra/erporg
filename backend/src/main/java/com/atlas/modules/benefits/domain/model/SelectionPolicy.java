package com.atlas.modules.benefits.domain.model;

import com.atlas.shared.domain.*;
import java.util.*;

public final class SelectionPolicy {
  private SelectionPolicy() {}

  public static List<Dependant> dependants(List<Dependant> entries) {
    if (entries == null || entries.size() > 5)
      throw new InputValidation(Map.of("dependants", "Provide zero to five dependants."));
    if (entries.stream().anyMatch(Objects::isNull))
      throw new InputValidation(
          Map.of("dependants", "Every dependant must have a name and relationship."));
    if (entries.stream().map(d -> d.name().toLowerCase(Locale.ROOT)).distinct().count()
        != entries.size())
      throw new InputValidation(
          Map.of("dependants", "Dependant names must be distinct within this selection."));
    return List.copyOf(entries);
  }

  public static void decide(String status, long version, Long expected, String outcome) {
    if (expected == null)
      throw new InputValidation(Map.of("version", "A request version is required."));
    if (!Set.of("APPROVED", "REJECTED").contains(outcome == null ? "" : outcome))
      throw new InputValidation(Map.of("outcome", "Choose Approve or Reject."));
    if (!status.equals("PENDING") || version != expected)
      throw new CommandConflict("Selection has already changed; refresh before reviewing.");
  }
}
