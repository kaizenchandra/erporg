package com.atlas.modules.documents.application.usecase;

import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.in.DocumentTemplatesUseCases;
import com.atlas.modules.documents.application.port.out.*;
import com.atlas.modules.documents.domain.model.DocumentTemplatePolicy;
import com.atlas.shared.domain.*;
import java.time.*;
import java.util.*;

public final class DocumentTemplatesService implements DocumentTemplatesUseCases {
  private final DocumentTemplateStore store;
  private final DocumentStore documents;
  private final DocumentIdentity identity;
  private final UnitOfWork tx;
  private final Clock clock;

  public DocumentTemplatesService(
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

  private List<DocumentTemplate> configured(String tenant) {
    var values = store.templates(tenant, LocalDate.now(clock));
    if (values.size() > 100)
      throw new CommandConflict("Template capacity exceeded; contact document operations.");
    return values;
  }

  public List<DocumentTemplate> templates(String subject) {
    return configured(identity.actor(subject).tenant());
  }

  public DocumentTemplate create(String subject, CreateDocumentTemplate c, String correlation) {
    var actor = identity.actor(subject);
    if (!actor.role().equals("HR")) throw new AccessDenied();
    if (c == null) throw new InputValidation(Map.of("code", "Template input is required."));
    DocumentTemplatePolicy.code(c.code());
    DocumentTemplatePolicy.text("title", c.title(), 100, false);
    DocumentTemplatePolicy.text("body", c.body(), 4000, true);
    DocumentTemplatePolicy.text("reason", c.reason(), 500, false);
    DocumentTemplatePolicy.key(c.idempotencyKey());
    if (c.required() == null)
      throw new InputValidation(Map.of("required", "Choose whether this template is required."));
    if (c.effectiveDate() == null)
      throw new InputValidation(Map.of("effectiveDate", "Choose an effective date."));
    return tx.run(
        () -> {
          var a = identity.actor(subject);
          if (!a.equals(actor)) throw new AccessDenied();
          store.lock(a.tenant());
          var old = store.templateDuplicate(a.tenant(), a.subject(), c.idempotencyKey());
          if (old.isPresent()) {
            var d = old.get();
            if (!d.code().equals(c.code())
                || !d.title().equals(c.title().trim())
                || !d.body().equals(c.body().trim())
                || d.required() != c.required()
                || !d.effectiveDate().equals(c.effectiveDate())
                || !d.reason().equals(c.reason().trim()))
              throw new CommandConflict("Template key was used for different input.");
            return d;
          }
          if (store.revisionDuplicate(a.tenant(), a.subject(), c.idempotencyKey()).isPresent())
            throw new CommandConflict("Key already used for template revision.");
          var configured = configured(a.tenant());
          if (configured.size() >= 100)
            throw new CommandConflict("100 templates configured; contact document operations.");
          if (configured.stream().anyMatch(t -> t.code().equals(c.code())))
            throw new CommandConflict("Template code already exists.");
          var value =
              new DocumentTemplate(
                  c.code(),
                  c.title().trim(),
                  c.body().trim(),
                  c.required(),
                  c.effectiveDate(),
                  a.subject(),
                  c.reason().trim(),
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  correlation);
          store.create(a.tenant(), value, c.idempotencyKey());
          return value;
        });
  }

  public DocumentEvidence link(
      String subject, String code, LinkDocumentEvidence c, String correlation) {
    var original = identity.actor(subject);
    DocumentTemplatePolicy.code(code);
    if (c == null)
      throw new InputValidation(Map.of("documentId", "Choose a current clean document."));
    int expected = DocumentTemplatePolicy.version(c.expectedVersion());
    DocumentTemplatePolicy.text("reason", c.reason(), 500, false);
    DocumentTemplatePolicy.key(c.idempotencyKey());
    if (c.documentId() == null || !c.documentId().matches("[a-fA-F0-9-]{36}"))
      throw new InputValidation(Map.of("documentId", "Choose a current clean document."));
    return tx.run(
        () -> {
          var a = identity.actor(subject);
          if (!a.equals(original)) throw new AccessDenied();
          store.lock(a.tenant());
          var d = documents.find(a.tenant(), c.documentId()).orElseThrow(AccessDenied::new);
          var employee = d.view().employeeId();
          if (!a.role().equals("HR") && !employee.equals(a.employeeId())) throw new AccessDenied();
          documents.lock(a.tenant(), employee);
          documents.locked(a.tenant(), d.view().rootId()).orElseThrow(AccessDenied::new);
          var previous = store.linkDuplicate(a.tenant(), a.subject(), c.idempotencyKey());
          if (previous.isPresent()) {
            var old = previous.get();
            if (!old.code().equals(code)
                || !old.rootId().equals(d.view().rootId())
                || old.linkedVersion() != expected
                || d.view().version() != expected
                || !old.reason().equals(c.reason().trim()))
              throw new CommandConflict("Evidence key was used for different input.");
            return old;
          }
          if (store
              .reclassificationDuplicate(a.tenant(), a.subject(), c.idempotencyKey())
              .isPresent())
            throw new CommandConflict("Key already used for classification correction.");
          if (configured(a.tenant()).stream().noneMatch(t -> t.code().equals(code) && !t.retired()))
            throw new InputValidation(Map.of("code", "Choose a configured template."));
          var latest = documents.versions(a.tenant(), d.view().rootId()).getFirst();
          if (!latest.id().equals(d.view().id()) || latest.version() != expected)
            throw new CommandConflict("Document version changed; refresh before linking.");
          if (!latest.status().equals("AVAILABLE"))
            throw new CommandConflict("Only a current clean document can be linked.");
          if (store.reserved(a.tenant(), d.view().rootId())
              || store.evidence(a.tenant(), employee).stream()
                  .anyMatch(e -> e.code().equals(code) || e.rootId().equals(d.view().rootId())))
            throw new CommandConflict(
                "Template or document chain is already linked; contact document operations.");
          var evidence =
              new DocumentEvidence(
                  code,
                  employee,
                  d.view().rootId(),
                  expected,
                  a.subject(),
                  c.reason().trim(),
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  correlation);
          store.link(a.tenant(), evidence, c.idempotencyKey());
          return evidence;
        });
  }

  private String employee(DocumentActor a, String id) {
    var target = id == null || id.isBlank() ? a.employeeId() : id;
    if (!target.matches("[A-Za-z0-9-]{1,40}"))
      throw new InputValidation(Map.of("employeeId", "Enter an employee identifier."));
    return identity.employee(a.subject(), target);
  }

  public List<DocumentEvidence> history(String subject, String employeeId) {
    var a = identity.actor(subject);
    return store.evidenceHistory(a.tenant(), employee(a, employeeId), null);
  }

  public DocumentCompleteness completeness(String subject, String employeeId) {
    var a = identity.actor(subject);
    var target = employee(a, employeeId);
    var evidence = store.evidence(a.tenant(), target);
    var today = LocalDate.now(clock);
    var items = new ArrayList<DocumentCompleteness.Item>();
    for (var t : configured(a.tenant())) {
      var link = evidence.stream().filter(e -> e.code().equals(t.code())).findFirst();
      DocumentView latest =
          link.map(e -> documents.versions(a.tenant(), e.rootId()).getFirst()).orElse(null);
      String status = latest == null ? null : latest.status();
      items.add(
          new DocumentCompleteness.Item(
              t.code(),
              t.title(),
              t.required(),
              t.effectiveDate(),
              t.retired()
                  ? "RETIRED"
                  : DocumentTemplatePolicy.completeness(
                      t.required(), t.effectiveDate(), today, status),
              link.map(DocumentEvidence::rootId).orElse(null),
              latest == null ? null : latest.id(),
              latest == null ? null : latest.version(),
              status));
    }
    return new DocumentCompleteness(target, today, List.copyOf(items));
  }
}
