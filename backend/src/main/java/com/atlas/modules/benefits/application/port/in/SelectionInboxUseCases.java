package com.atlas.modules.benefits.application.port.in;

import com.atlas.modules.benefits.application.model.*;

public interface SelectionInboxUseCases {
  SelectionPage inbox(String subject, SelectionInboxQuery query);
}
