package com.atlas.modules.benefits.application.port.out;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.domain.model.BenefitPlan;
import java.time.*;
import java.util.*;

public interface SelectionStore {
  void lockEmployee(String tenant, String employeeId);

  Optional<BenefitPlan> plan(String tenant, String code);

  Optional<SelectionView> duplicate(String tenant, String subject, String key);

  Optional<SelectionView> locked(String tenant, String id);

  boolean active(String tenant, String employeeId, String planCode);

  SelectionView insert(
      BenefitContext actor,
      SubmitSelection input,
      BenefitPlan plan,
      LocalDate date,
      Instant now,
      String correlation);

  SelectionView decision(
      String tenant,
      String id,
      String actor,
      DecideSelection input,
      Instant now,
      String correlation);

  SelectionPage list(String tenant, String employeeId, int page, int size);
}
