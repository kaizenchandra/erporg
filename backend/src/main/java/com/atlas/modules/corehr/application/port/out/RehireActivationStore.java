package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.RehireActivationModels.Completion;
import com.atlas.modules.corehr.domain.model.RehireActivation;
import java.util.*;

public interface RehireActivationStore {
  List<String> identities(String tenant, String employeeId);

  List<RehireActivation> history(String tenant, String employeeId);

  Optional<RehireActivation> duplicate(String tenant, String actor, String key, boolean review);

  Optional<Completion> completion(String tenant, String employeeId);

  void submit(String tenant, RehireActivation record, String key);

  void review(String tenant, RehireActivation record, String key, String epoch);
}
