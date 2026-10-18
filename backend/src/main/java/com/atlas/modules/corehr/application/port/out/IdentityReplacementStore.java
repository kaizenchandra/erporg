package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.domain.model.IdentityReplacement;
import java.util.*;

public interface IdentityReplacementStore {
  List<String> candidates(String tenant, String employee);

  List<IdentityReplacement> history(String tenant, String employee);

  Optional<IdentityReplacement> duplicate(String tenant, String actor, String key, boolean review);

  void submit(String tenant, IdentityReplacement record, String key);

  void review(String tenant, IdentityReplacement record, String key, String epoch);
}
