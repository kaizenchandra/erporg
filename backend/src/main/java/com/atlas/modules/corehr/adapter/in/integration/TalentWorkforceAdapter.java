package com.atlas.modules.corehr.adapter.in.integration;

import com.atlas.modules.corehr.api.TalentWorkforceAccess;
import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.domain.model.Employee;

public final class TalentWorkforceAdapter implements TalentWorkforceAccess {
  private final PeopleUseCases people;
  private final HiringUseCases hires;
  private final TalentRoleUseCases roles;

  public TalentWorkforceAdapter(PeopleUseCases p, HiringUseCases h, TalentRoleUseCases r) {
    people = p;
    hires = h;
    roles = r;
  }

  public java.util.List<String> hrReviewers(String s) {
    return roles.hrReviewers(s);
  }

  public boolean hasRole(String s, String e, String r) {
    return roles.hasRole(s, e, r);
  }

  public Identity self(String s) {
    var a = people.actor(s);
    return new Identity(a.subject(), a.tenant(), a.employeeId(), a.role());
  }

  private Worker map(Employee e) {
    return new Worker(
        e.id(),
        e.name(),
        e.title(),
        e.department(),
        e.managerId(),
        e.status(),
        e.startDate(),
        e.version());
  }

  public Worker worker(String s, String id) {
    return map(people.profile(people.actor(s), id));
  }

  public Workers workers(String s, String q, int page) {
    var p = people.directory(people.actor(s), new DirectoryQuery(q, "", "", page, 25, "asc"));
    return new Workers(p.items().stream().map(this::map).toList(), p.total());
  }

  public Hire submitHire(String s, HireInput i, String reason, String key, String corr) {
    var h =
        hires.submit(
            people.actor(s),
            new SubmitHire(
                i.name(),
                i.email(),
                i.phone(),
                i.title(),
                i.department(),
                i.location(),
                i.managerId(),
                i.start(),
                reason,
                key),
            corr);
    return new Hire(h.id(), h.status(), h.employeeId());
  }

  public Hire decideHire(String s, String id, String decision, String reason, String corr) {
    var h = hires.decide(people.actor(s), id, new Commands.Decide(decision, reason), corr);
    return new Hire(h.id(), h.status(), h.employeeId());
  }

  public void completeOnboarding(
      String s, String id, long v, String reason, String key, String corr) {
    hires.completeOnboarding(people.actor(s), id, new CompleteOnboarding(reason, v, key), corr);
  }
}
