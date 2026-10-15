package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.port.in.OrganisationBrowseUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.shared.domain.AccessDenied;
import java.time.*;

public final class OrganisationBrowseService implements OrganisationBrowseUseCases {
  private final PeopleStore people;
  private final OrganisationChartStore chart;
  private final UnitOfWork tx;
  private final Clock clock;

  public OrganisationBrowseService(
      PeopleStore people, OrganisationChartStore chart, UnitOfWork tx, Clock clock) {
    this.people = people;
    this.chart = chart;
    this.tx = tx;
    this.clock = clock;
  }

  public OrganisationBranch browse(String subject, OrganisationChartQuery q) {
    return tx.run(
        () -> {
          var date = LocalDate.now(clock);
          var actor = people.actor(subject, date);
          var scope = PeopleReadScope.of(actor);
          OrganisationNode parent = null, manager = null;
          boolean outside = false;
          if (!q.parentId().isEmpty()) {
            var entry = chart.find(scope, q.parentId(), date).orElseThrow(AccessDenied::new);
            if (!actor.canReadEmployee(entry.node().id(), entry.managerId()))
              throw new AccessDenied();
            parent = entry.node();
            if (entry.managerId() != null) {
              var m = chart.find(scope, entry.managerId(), date);
              if (m.isPresent() && actor.canReadEmployee(m.get().node().id(), m.get().managerId()))
                manager = m.get().node();
              else outside = true;
            }
          }
          var page = chart.page(scope, q, date);
          return new OrganisationBranch(
              parent,
              manager,
              outside,
              page.items(),
              page.total(),
              q.page(),
              q.size(),
              date,
              !q.search().isEmpty() ? "SEARCH" : q.parentId().isEmpty() ? "ROOTS" : "REPORTS");
        });
  }
}
