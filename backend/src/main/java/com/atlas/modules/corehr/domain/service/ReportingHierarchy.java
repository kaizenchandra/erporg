package com.atlas.modules.corehr.domain.service;

import com.atlas.modules.corehr.domain.model.*;
import java.time.LocalDate;
import java.util.*;

public final class ReportingHierarchy {
  private ReportingHierarchy() {}

  public static void validate(
      List<ReportingLink> links, String employeeId, String managerId, LocalDate date) {
    var graph = new HashMap<String, ReportingLink>();
    for (var link : links) graph.put(link.id(), link);
    var employee = graph.get(employeeId);
    var manager = graph.get(managerId);
    if (employee == null || manager == null)
      throw new ChangeRequest.Validation(
          Map.of("newValue", "Choose a manager in this organisation."));
    if (date.isBefore(employee.startDate()) || date.isBefore(manager.startDate()))
      throw new ChangeRequest.Validation(
          Map.of(
              "effectiveDate",
              "Employee and manager must have started by the reporting-change date."));
    var visited = new HashSet<String>();
    visited.add(employeeId);
    String ancestor = managerId;
    while (ancestor != null) {
      if (!visited.add(ancestor))
        throw new ChangeRequest.Validation(
            Map.of("newValue", "This reporting change would create a cycle."));
      var link = graph.get(ancestor);
      if (link == null)
        throw new ChangeRequest.Conflict("Reporting hierarchy has an unavailable ancestor.");
      ancestor = link.managerId();
    }
  }
}
