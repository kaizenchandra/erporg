package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.InitialIdentityModels.*;
import java.util.*;

public interface InitialIdentityStore {
  void lock(String subject);

  boolean eligible(String tenant, String employee, String subject);

  Optional<Request> replay(String tenant, String actor, String key);

  Optional<Request> find(String tenant, String id);

  Optional<Decision> decision(String tenant, String id);

  List<Binding> bindings(String tenant, String employee);

  Optional<String> issuer(String subject);

  void insert(String tenant, Request request);

  void decide(String tenant, Request request, Decision decision, String name);
}
