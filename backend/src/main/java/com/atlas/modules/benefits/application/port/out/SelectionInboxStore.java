package com.atlas.modules.benefits.application.port.out;

import com.atlas.modules.benefits.application.model.*;

public interface SelectionInboxStore {
  SelectionPage inbox(SelectionInboxScope scope, SelectionInboxQuery query);
}
