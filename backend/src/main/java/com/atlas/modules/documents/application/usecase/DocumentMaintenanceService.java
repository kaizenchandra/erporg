package com.atlas.modules.documents.application.usecase;

import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.in.DocumentMaintenanceUseCases;
import com.atlas.modules.documents.application.port.out.*;
import com.atlas.modules.documents.domain.model.DocumentTemplatePolicy;
import com.atlas.shared.domain.*;
import java.time.*;
import java.util.*;

public final class DocumentMaintenanceService implements DocumentMaintenanceUseCases {
  private final DocumentTemplateStore store;
  private final DocumentStore documents;
  private final DocumentIdentity identity;
  private final UnitOfWork tx;
  private final Clock clock;

  public DocumentMaintenanceService(
      DocumentTemplateStore store,
      DocumentStore documents,
      DocumentIdentity identity,
      UnitOfWork tx,
      Clock clock) {
    this.store = store;
    this.documents = documents;
    this.identity = identity;
    this.tx = tx;
    this.clock = clock;
  }

  private DocumentActor hr(String subject) {
    var a = identity.actor(subject);
    if (!a.role().equals("HR")) throw new AccessDenied();
    return a;
  }

  public List<DocumentTemplate> revisions(String subject, String code) {
    var a = identity.actor(subject);
    DocumentTemplatePolicy.code(code);
    var history = store.revisions(a.tenant(), code);
    if (history.isEmpty()) throw new AccessDenied();
    return history;
  }

  public DocumentTemplate revise(
      String subject, String code, ReviseDocumentTemplate c, String correlation) {
    var original = hr(subject);
    DocumentTemplatePolicy.code(code);
    if (c == null) throw new InputValidation(Map.of("title", "Revision input required."));
    int expected = DocumentTemplatePolicy.version(c.expectedVersion());
    DocumentTemplatePolicy.text("title", c.title(), 100, false);
    DocumentTemplatePolicy.text("body", c.body(), 4000, true);
    DocumentTemplatePolicy.text("reason", c.reason(), 500, false);
    DocumentTemplatePolicy.key(c.idempotencyKey());
    if (c.required() == null || c.retired() == null)
      throw new InputValidation(Map.of("required", "Required and retired flags must be explicit."));
    if (c.effectiveDate() == null)
      throw new InputValidation(Map.of("effectiveDate", "Choose an effective date."));
    return tx.run(
        () -> {
          var a = hr(subject);
          if (!a.equals(original)) throw new AccessDenied();
          store.lock(a.tenant());
          var previous = store.revisionDuplicate(a.tenant(), a.subject(), c.idempotencyKey());
          if (previous.isPresent()) {
            var d = previous.get();
            if (!d.code().equals(code)
                || d.version() != expected + 1
                || !d.title().equals(c.title().trim())
                || !d.body().equals(c.body().trim())
                || d.required() != c.required()
                || d.retired() != c.retired()
                || !d.effectiveDate().equals(c.effectiveDate())
                || !d.reason().equals(c.reason().trim()))
              throw new CommandConflict("Revision key was used for different input.");
            return d;
          }
          if (store.templateDuplicate(a.tenant(), a.subject(), c.idempotencyKey()).isPresent())
            throw new CommandConflict("Key already used for template creation.");
          var all = store.revisions(a.tenant(), code);
          if (all.isEmpty()) throw new AccessDenied();
          var latest = all.getFirst();
          DocumentTemplatePolicy.revision(
              latest.version(),
              expected,
              latest.retired(),
              latest.effectiveDate(),
              c.effectiveDate(),
              LocalDate.now(clock));
          var value =
              new DocumentTemplate(
                  code,
                  c.title().trim(),
                  c.body().trim(),
                  c.required(),
                  c.effectiveDate(),
                  a.subject(),
                  c.reason().trim(),
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  correlation,
                  expected + 1,
                  c.retired());
          store.revise(a.tenant(), value, c.idempotencyKey());
          return value;
        });
  }

  public DocumentEvidence reclassify(
      String subject, String code, ReclassifyDocument c, String correlation) {
    var original = hr(subject);
    DocumentTemplatePolicy.code(code);
    if (c == null) throw new InputValidation(Map.of("documentId", "Select a replacement chain."));
    if (c.employeeId() == null || !c.employeeId().matches("[A-Za-z0-9-]{1,40}"))
      throw new InputValidation(Map.of("employeeId", "An employee identifier is required."));
    int fileVersion = DocumentTemplatePolicy.version(c.expectedVersion()),
        expected = DocumentTemplatePolicy.classificationVersion(c.expectedClassificationVersion());
    DocumentTemplatePolicy.text("reason", c.reason(), 500, false);
    DocumentTemplatePolicy.key(c.idempotencyKey());
    if (c.documentId() == null || !c.documentId().matches("[a-fA-F0-9-]{36}"))
      throw new InputValidation(Map.of("documentId", "Select a current clean document."));
    return tx.run(
        () -> {
          var a = hr(subject);
          if (!a.equals(original)) throw new AccessDenied();
          var employee = identity.employee(subject, c.employeeId());
          store.lock(a.tenant());
          documents.lock(a.tenant(), employee);
          var document = documents.find(a.tenant(), c.documentId()).orElseThrow(AccessDenied::new);
          if (!document.view().employeeId().equals(employee)) throw new AccessDenied();
          documents.locked(a.tenant(), document.view().rootId()).orElseThrow(AccessDenied::new);
          var duplicate =
              store.reclassificationDuplicate(a.tenant(), a.subject(), c.idempotencyKey());
          if (duplicate.isPresent()) {
            var d = duplicate.get();
            if (!d.code().equals(code)
                || !d.employeeId().equals(employee)
                || !d.rootId().equals(document.view().rootId())
                || d.linkedVersion() != fileVersion
                || document.view().version() != fileVersion
                || d.classificationVersion() != expected + 1
                || !d.reason().equals(c.reason().trim()))
              throw new CommandConflict("Correction key was used for different input.");
            return d;
          }
          if (store.linkDuplicate(a.tenant(), a.subject(), c.idempotencyKey()).isPresent())
            throw new CommandConflict("Key already used for initial classification.");
          var current =
              store.evidence(a.tenant(), employee).stream()
                  .filter(e -> e.code().equals(code))
                  .findFirst()
                  .orElseThrow(() -> new CommandConflict("An initial classification is required."));
          DocumentTemplatePolicy.correction(current.classificationVersion(), expected);
          var templates = store.templates(a.tenant(), LocalDate.now(clock));
          var template =
              templates.stream()
                  .filter(t -> t.code().equals(code))
                  .findFirst()
                  .orElseThrow(AccessDenied::new);
          if (template.retired())
            throw new CommandConflict("Retired templates cannot be reclassified.");
          var latest = documents.versions(a.tenant(), document.view().rootId()).getFirst();
          if (!latest.id().equals(c.documentId())
              || latest.version() != fileVersion
              || !latest.status().equals("AVAILABLE"))
            throw new CommandConflict(
                "Select a current clean version and refresh before correcting.");
          if (store.reserved(a.tenant(), document.view().rootId()))
            throw new CommandConflict("This chain is already reserved in classification history.");
          var value =
              new DocumentEvidence(
                  code,
                  employee,
                  latest.rootId(),
                  fileVersion,
                  a.subject(),
                  c.reason().trim(),
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  correlation,
                  expected + 1,
                  current.rootId());
          store.reclassify(a.tenant(), value, c.idempotencyKey());
          return value;
        });
  }

  public List<DocumentEvidence> classifications(String subject, String code, String employeeId) {
    var a = identity.actor(subject);
    DocumentTemplatePolicy.code(code);
    var employee =
        identity.employee(
            subject, employeeId == null || employeeId.isBlank() ? a.employeeId() : employeeId);
    return store.evidenceHistory(a.tenant(), employee, code);
  }
}
