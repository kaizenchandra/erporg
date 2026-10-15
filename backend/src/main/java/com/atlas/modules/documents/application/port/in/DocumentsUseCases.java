package com.atlas.modules.documents.application.port.in;

import com.atlas.modules.documents.application.model.*;
import java.util.List;

public interface DocumentsUseCases {
  DocumentView version(String subject, String id, VersionDocument input, String correlation);

  List<DocumentView> versions(String subject, String id);

  DocumentView retry(String subject, String id, RetryDocumentScan input, String correlation);

  DocumentView upload(String subject, UploadDocument input, String correlation);

  DocumentPage list(String subject, String search, int page, int size);

  DownloadDocument download(String subject, String id, String correlation);

  List<DocumentAudit> history(String subject, String id);
}
