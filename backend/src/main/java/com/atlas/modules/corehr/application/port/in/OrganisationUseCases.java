package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.LocalDate;
import java.util.List;

public interface OrganisationUseCases {
  List<Department> departments(Actor actor);

  Department create(Actor actor, CreateDepartment command, String correlation);

  List<DepartmentAudit> history(Actor actor);

  void requireDepartment(Actor actor, String name, LocalDate date);
}
