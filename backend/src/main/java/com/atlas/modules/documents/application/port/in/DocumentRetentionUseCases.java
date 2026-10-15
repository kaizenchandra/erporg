package com.atlas.modules.documents.application.port.in;

import com.atlas.modules.documents.application.model.*;

public interface DocumentRetentionUseCases {
  RetentionReview review(String subject, String documentId);

  RetentionEvent command(
      String subject, String documentId, RetentionCommand input, String correlation);
}
