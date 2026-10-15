package com.atlas.modules.documents.application.port.in;

import com.atlas.modules.documents.application.model.*;
import java.util.List;

public interface DocumentTemplatesUseCases {
  List<DocumentTemplate> templates(String subject);

  DocumentTemplate create(String subject, CreateDocumentTemplate input, String correlation);

  DocumentEvidence link(
      String subject, String code, LinkDocumentEvidence input, String correlation);

  DocumentCompleteness completeness(String subject, String employeeId);

  List<DocumentEvidence> history(String subject, String employeeId);
}
