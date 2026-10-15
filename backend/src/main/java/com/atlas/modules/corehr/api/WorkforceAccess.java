package com.atlas.modules.corehr.api;

import java.time.LocalDate;

public interface WorkforceAccess {
  Context self(String subject);

  Context employee(String subject, String employeeId);

  record Context(
      String subject,
      String tenant,
      String employeeId,
      String role,
      String employmentStatus,
      LocalDate employmentStart) {}
}
