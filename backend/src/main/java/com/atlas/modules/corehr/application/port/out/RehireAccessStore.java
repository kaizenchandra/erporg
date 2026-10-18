package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.domain.model.RehireAccessRevocation;
import java.util.Optional;

public interface RehireAccessStore {
  Optional<com.atlas.modules.corehr.application.model.RehireAccessModels.Grant> grant(
      String tenant, String employeeId);

  java.util.List<com.atlas.modules.corehr.application.model.RehireAccessModels.Grant> grants(
      String tenant, String employeeId);

  java.util.List<RehireAccessRevocation> revocations(String tenant, String employeeId);

  Optional<RehireAccessRevocation> revocation(String tenant, String employeeId);

  Optional<RehireAccessRevocation> duplicate(String tenant, String actor, String key);

  void revoke(String tenant, RehireAccessRevocation record, String key);
}
