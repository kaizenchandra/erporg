package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.LegalEntityRetirementModels.*;
import com.atlas.modules.corehr.application.model.LegalEntityRetirementModels.NotificationMember;
import com.atlas.modules.corehr.domain.model.LegalEntityRetirement.*;
import java.time.Instant;
import java.util.*;

public interface LegalEntityRetirementStore {
  InboxPage inbox(InboxScope scope, InboxQuery query);

  List<NotificationMember> notificationCandidates(String tenant);

  void notify(String tenant, String subject, String message, Instant recordedAt);

  List<State> history(String tenant, String code);

  Optional<Proposal> duplicate(String tenant, String actor, String key);

  Optional<Decision> duplicateDecision(String tenant, String actor, String key);

  void insert(String tenant, Proposal proposal, String key);

  void insertDecision(String tenant, String code, Decision decision, String key);
}
