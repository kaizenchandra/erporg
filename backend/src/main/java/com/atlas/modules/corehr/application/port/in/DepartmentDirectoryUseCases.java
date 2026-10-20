package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.DepartmentDirectoryModels.*;
import com.atlas.modules.corehr.domain.model.Actor;

public interface DepartmentDirectoryUseCases {
  Page browse(Actor actor, Query query);
}
