package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.domain.model.Resignation;
import java.util.List;

public interface ResignationUseCases {
  ResignationPage list(String subject, RequestQuery query);

  com.atlas.modules.corehr.domain.model.CaseEpisode episode(String subject, String id);

  Resignation submit(String subject, ResignationCommands.Submit command, String correlation);

  Resignation review(
      String subject, String id, ResignationCommands.Review command, String correlation);

  com.atlas.modules.corehr.domain.model.ResignationWithdrawal withdraw(
      String subject, String id, ResignationCommands.Withdraw command, String correlation);

  java.util.Optional<com.atlas.modules.corehr.domain.model.ResignationWithdrawal> withdrawal(
      String subject, String id);

  List<ResignationAudit> history(String subject, String id);
}
