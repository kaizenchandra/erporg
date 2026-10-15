package com.atlas.modules.benefits.application.port.out;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.domain.model.BenefitPlan;
import java.time.Instant;
import java.util.*;

public interface PlanStore {
  List<BenefitPlan> plans(String tenant, int limit);

  Optional<CreatePlan> duplicate(String tenant, String subject, String key);

  void insert(
      String tenant,
      String actor,
      BenefitPlan plan,
      String reason,
      String key,
      Instant now,
      String correlation);

  List<PlanAudit> history(String tenant);
}
