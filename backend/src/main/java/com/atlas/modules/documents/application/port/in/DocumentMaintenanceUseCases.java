package com.atlas.modules.documents.application.port.in;

import com.atlas.modules.documents.application.model.*;
import java.util.List;

public interface DocumentMaintenanceUseCases {
  DocumentTemplate revise(
      String subject, String code, ReviseDocumentTemplate input, String correlation);

  List<DocumentTemplate> revisions(String subject, String code);

  DocumentEvidence reclassify(
      String subject, String code, ReclassifyDocument input, String correlation);

  List<DocumentEvidence> classifications(String subject, String code, String employeeId);
}
