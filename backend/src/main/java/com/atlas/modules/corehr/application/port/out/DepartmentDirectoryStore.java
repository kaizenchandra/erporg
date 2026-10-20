package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.DepartmentDirectoryModels.*;
import java.time.LocalDate;

public interface DepartmentDirectoryStore {
  Page browse(String tenant, Query query, LocalDate date);
}
