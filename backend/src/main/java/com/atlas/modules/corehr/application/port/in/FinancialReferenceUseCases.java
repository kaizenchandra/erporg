package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.FinancialReferenceModels.*;

public interface FinancialReferenceUseCases {
  Entity entity(String subject, String code);

  Worker worker(String subject, String id);
}
