package com.atlas.modules.documents.application.port.out;

import com.atlas.modules.documents.application.model.*;
import java.util.*;

public interface DocumentTemplateStore {
  List<DocumentTemplate> revisions(String tenant, String code);

  Optional<DocumentTemplate> revisionDuplicate(String tenant, String actor, String key);

  void revise(String tenant, DocumentTemplate value, String key);

  Optional<DocumentEvidence> reclassificationDuplicate(String tenant, String actor, String key);

  void reclassify(String tenant, DocumentEvidence value, String key);

  List<DocumentEvidence> evidenceHistory(String tenant, String employeeId, String code);

  boolean reserved(String tenant, String rootId);

  void lock(String tenant);

  List<DocumentTemplate> templates(String tenant, java.time.LocalDate asOf);

  Optional<DocumentTemplate> templateDuplicate(String tenant, String actor, String key);

  void create(String tenant, DocumentTemplate value, String key);

  Optional<DocumentEvidence> linkDuplicate(String tenant, String actor, String key);

  void link(String tenant, DocumentEvidence value, String key);

  List<DocumentEvidence> evidence(String tenant, String employeeId);
}
