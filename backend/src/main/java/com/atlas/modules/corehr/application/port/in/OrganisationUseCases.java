package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.LocalDate;
import java.util.List;

public interface OrganisationUseCases {
  List<Department> departments(Actor actor);

  PositionPage positions(Actor actor, PositionQuery query);

  Position createPosition(Actor actor, CreatePosition command, String correlation);

  List<PositionAudit> positionHistory(Actor actor);

  Department create(Actor actor, CreateDepartment command, String correlation);

  List<DepartmentAudit> history(Actor actor);

  void requirePosition(Actor actor, String code, String departmentName, LocalDate date);

  void requireDepartment(Actor actor, String name, LocalDate date);
}
