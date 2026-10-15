package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.*;

public interface OrganisationBrowseUseCases {
  OrganisationBranch browse(String subject, OrganisationChartQuery query);
}
