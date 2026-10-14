package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.CreateDepartment;
import com.atlas.modules.corehr.application.model.DepartmentAudit;
import com.atlas.modules.corehr.domain.model.Department;
import java.time.Instant;
import java.util.*;

public interface OrganisationStore {
  List<Department> departments(String tenant);

  Optional<Department> department(String tenant, String code);

  Optional<Department> byName(String tenant, String name);

  Optional<CreateDepartment> duplicate(String tenant, String subject, String key);

  void insert(
      String tenant,
      String subject,
      Department department,
      String reason,
      String key,
      Instant now,
      String correlation);

  List<DepartmentAudit> history(String tenant);
}
