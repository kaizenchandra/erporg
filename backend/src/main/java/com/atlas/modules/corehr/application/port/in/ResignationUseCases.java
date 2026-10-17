package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.domain.model.Resignation;
import java.util.List;

public interface ResignationUseCases {
  ResignationPage list(String subject, RequestQuery query);

  Resignation submit(String subject, ResignationCommands.Submit command, String correlation);

  Resignation review(
      String subject, String id, ResignationCommands.Review command, String correlation);

  List<ResignationAudit> history(String subject, String id);
}
