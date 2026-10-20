package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.FinancialReferenceModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.shared.domain.AccessDenied;
import java.util.Comparator;

/**
 * Current authorised worker and declared-reference projection; does not establish statutory
 * eligibility.
 */
public final class FinancialReferenceService implements FinancialReferenceUseCases {
  private final PeopleUseCases people;
  private final LegalEntityStore entities;
  private final LegalEntityRetirementStore retirements;
  private final UnitOfWork reads;

  public FinancialReferenceService(
      PeopleUseCases people,
      LegalEntityStore entities,
      LegalEntityRetirementStore retirements,
      UnitOfWork reads) {
    this.people = people;
    this.entities = entities;
    this.retirements = retirements;
    this.reads = reads;
  }

  public Entity entity(String subject, String code) {
    return reads.run(
        () -> {
          var a = people.actor(subject);
          var e = entities.find(a.tenant(), code).orElseThrow(AccessDenied::new).entity();
          var retired =
              retirements.history(a.tenant(), code).stream()
                  .filter(x -> x.decision() != null && x.decision().decision().equals("APPROVED"))
                  .map(x -> x.request().effectiveDate())
                  .min(Comparator.naturalOrder())
                  .orElse(null);
          return new Entity(e.code(), e.currencyCode(), e.effectiveFrom(), retired);
        });
  }

  public Worker worker(String subject, String id) {
    return reads.run(
        () -> {
          var e = people.profile(people.actor(subject), id);
          return new Worker(e.id(), e.status(), e.startDate(), e.legalEntityCode());
        });
  }
}
