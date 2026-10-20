package com.atlas.modules.corehr.domain.model;

import com.atlas.shared.domain.*;
import java.time.LocalDate;
import java.util.*;

public final class DepartmentHierarchy {
  private DepartmentHierarchy() {}

  public record Node(String code, String parentCode, LocalDate effectiveFrom) {}

  public static void ensureCorrection(
      String code, String parent, List<Node> nodes, LocalDate date) {
    var graph = new HashMap<String, Node>();
    nodes.forEach(n -> graph.put(n.code(), n));
    var target = graph.get(code);
    if (target == null) throw new AccessDenied();
    if (target.effectiveFrom().isAfter(date))
      throw new InputValidation(
          Map.of("parentCode", "Only currently effective departments can be corrected."));
    if (Objects.equals(target.parentCode(), parent))
      throw new InputValidation(
          Map.of("parentCode", "Choose a different parent or root department."));
    var seen = new HashSet<String>();
    seen.add(code);
    for (String cursor = parent; cursor != null; ) {
      if (!seen.add(cursor))
        throw new InputValidation(
            Map.of("parentCode", "The proposed parent would create a hierarchy cycle."));
      var node = graph.get(cursor);
      if (node == null || node.effectiveFrom().isAfter(date))
        throw new InputValidation(
            Map.of("parentCode", "Choose a currently effective department in this organisation."));
      cursor = node.parentCode();
    }
  }
}
