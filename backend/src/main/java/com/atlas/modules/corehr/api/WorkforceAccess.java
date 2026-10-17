package com.atlas.modules.corehr.api;

import java.time.LocalDate;

public interface WorkforceAccess {
  void requireActive(String subject);

  String sessionEpoch(String subject);

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
