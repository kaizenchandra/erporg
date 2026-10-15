package com.atlas.modules.documents.application.port.out;

import com.atlas.modules.documents.application.model.*;
import java.util.*;

public interface DocumentStore {
  void lock(String tenant, String employeeId);

  Optional<StoredDocument> duplicate(String tenant, String subject, String key);

  Optional<StoredDocument> find(String tenant, String id);

  Optional<StoredDocument> locked(String tenant, String id);

  Optional<DocumentScanRetry> retryDuplicate(String tenant, String id, String actor, String key);

  void retry(String tenant, String id, DocumentScanRetry retry, String sha256);

  List<DocumentView> versions(String tenant, String rootId);

  Optional<String> parent(String tenant, String id);

  void linkVersion(String tenant, String parent, StoredDocument document, String correlation);

  long employeeCount(String tenant, String employeeId);

  void insert(String tenant, StoredDocument document, String correlation);

  DocumentPage list(String tenant, String employeeId, String search, int page, int size);

  List<DocumentAudit> history(String tenant, String id);

  void downloaded(
      String tenant, String id, String actor, java.time.Instant now, String correlation);
}
