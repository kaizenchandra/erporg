package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.Decide;
import com.atlas.modules.corehr.domain.model.*;
import java.util.List;

public interface HiringUseCases {
  HirePage hires(Actor actor, RequestQuery query);

  HireRequest submit(Actor actor, SubmitHire command, String correlation);

  HireRequest decide(Actor actor, String id, Decide command, String correlation);

  List<HireAudit> history(Actor actor, String id);
}
