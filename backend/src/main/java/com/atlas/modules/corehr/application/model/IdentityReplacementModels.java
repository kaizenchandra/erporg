package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.IdentityReplacement;
import java.math.BigDecimal;
import java.util.List;

public final class IdentityReplacementModels {
  private IdentityReplacementModels() {}

  public record Submit(String subject, String reason, BigDecimal version, String idempotencyKey) {}

  public record Review(String decision, String reason, BigDecimal version, String idempotencyKey) {}

  public record Context(
      String employeeId,
      long employeeVersion,
      String employmentStatus,
      RehireAccessModels.Grant grant,
      String accessStatus,
      List<RehireAccessModels.Grant> grants,
      List<com.atlas.modules.corehr.domain.model.RehireAccessRevocation> revocations,
      List<String> candidates,
      List<IdentityReplacement> history,
      boolean canRequest) {}
}
