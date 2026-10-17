package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.EmploymentEndModels.*;
import com.atlas.modules.corehr.domain.model.EmploymentEnd;
import com.atlas.modules.corehr.domain.model.EndWithdrawal;

public interface EmploymentEndUseCases {
  Context context(String subject, String requestId);

  EmploymentEnd confirm(String subject, String requestId, Confirm command, String correlation);

  EndWithdrawal withdraw(String subject, String requestId, Confirm command, String correlation);

  EndWithdrawal reviewWithdrawal(
      String subject,
      String requestId,
      String withdrawalId,
      ReviewWithdrawal command,
      String correlation);
}
