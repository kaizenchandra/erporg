package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.PositionDirectoryModels.*;

public interface PositionDirectoryUseCases {
  Page browse(String subject, Query query);
}
