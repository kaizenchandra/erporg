package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.*;
import java.time.LocalDate;
import java.util.*;

public interface OrganisationChartStore {
  Optional<OrganisationEntry> find(PeopleReadScope scope, String id, LocalDate asOf);

  Page page(PeopleReadScope scope, OrganisationChartQuery query, LocalDate asOf);

  record Page(List<OrganisationNode> items, long total) {}
}
