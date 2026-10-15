package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.domain.model.Employee;
import com.atlas.modules.corehr.domain.model.HireRequest;
import java.time.Instant;
import java.util.*;

public interface HiringStore {
  Optional<OnboardingCompletion> completionReplay(String tenant, String actor, String key);

  Optional<OnboardingCompletion> completion(String tenant, String hireId);

  void completeOnboarding(
      String tenant, OnboardingCompletion completion, Employee employee, String key);

  List<OnboardingCompletion> employmentHistory(String tenant, String employeeId);

  HirePage hires(String tenant, RequestQuery query);

  Optional<HireRequest> duplicate(String tenant, String subject, String key);

  HireRequest hire(String tenant, String id, boolean lock);

  boolean emailExists(String tenant, String email);

  void insert(String tenant, HireRequest request, String key, String correlation);

  void decide(
      String tenant,
      HireRequest request,
      String actor,
      String decision,
      String comment,
      Employee employee,
      Instant now,
      String correlation);

  List<HireAudit> history(String tenant, String id);
}
