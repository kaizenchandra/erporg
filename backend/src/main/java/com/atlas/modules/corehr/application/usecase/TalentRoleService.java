package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.TalentRoleStore;

public final class TalentRoleService implements TalentRoleUseCases {
  private final PeopleUseCases people;
  private final TalentRoleStore store;
  private final com.atlas.modules.corehr.application.port.out.PeopleStore peopleStore;
  private final java.time.Clock clock;

  public TalentRoleService(
      PeopleUseCases p,
      TalentRoleStore s,
      com.atlas.modules.corehr.application.port.out.PeopleStore ps,
      java.time.Clock c) {
    people = p;
    store = s;
    peopleStore = ps;
    clock = c;
  }

  public java.util.List<String> hrReviewers(String subject) {
    var a = people.actor(subject);
    return peopleStore.hrNotificationRecipients(a.tenant(), java.time.LocalDate.now(clock));
  }

  public boolean hasRole(String subject, String employee, String role) {
    var a = people.actor(subject);
    people.profile(a, employee);
    return role.equals("ALL") || store.hasRole(a.tenant(), employee, role);
  }
}
