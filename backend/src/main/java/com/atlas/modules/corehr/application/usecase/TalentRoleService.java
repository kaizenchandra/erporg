package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.TalentRoleStore;

public final class TalentRoleService implements TalentRoleUseCases {
  private final PeopleUseCases people;
  private final TalentRoleStore store;

  public TalentRoleService(PeopleUseCases p, TalentRoleStore s) {
    people = p;
    store = s;
  }

  public boolean hasRole(String subject, String employee, String role) {
    var a = people.actor(subject);
    people.profile(a, employee);
    return role.equals("ALL") || store.hasRole(a.tenant(), employee, role);
  }
}
