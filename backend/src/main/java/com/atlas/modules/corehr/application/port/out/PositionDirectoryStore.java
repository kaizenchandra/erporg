package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.PositionDirectoryModels.*;
import java.time.LocalDate;

public interface PositionDirectoryStore {
  Batch browse(String tenant, Query query, LocalDate evaluatedOn);
}
