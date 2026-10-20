package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.DepartmentParentModels.*;

public interface DepartmentParentUseCases {
  Context context(String subject, String code, int page, int size);

  Change correct(String subject, String code, Command command, String correlation);
}
