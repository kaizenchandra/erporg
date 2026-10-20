package com.atlas.modules.corehr.api;

import java.time.LocalDate;
import java.util.List;

/** Published current scope for timekeeping; no private HR fields. */
public interface TimeWorkforceAccess {
  Context self(String subject);

  Context employee(String subject, String employeeId);

  Scope scope(String subject);

  record Context(
      String subject,
      String tenant,
      String employeeId,
      String role,
      String status,
      LocalDate start) {}

  record Scope(Context actor, List<String> employeeIds, boolean all) {
    public Scope {
      employeeIds = List.copyOf(employeeIds);
    }
  }
}
