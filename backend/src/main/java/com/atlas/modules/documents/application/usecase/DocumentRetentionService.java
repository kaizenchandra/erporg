package com.atlas.modules.documents.application.usecase;

import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.in.DocumentRetentionUseCases;
import com.atlas.modules.documents.application.port.out.*;
import com.atlas.modules.documents.domain.model.*;
import com.atlas.shared.domain.*;
import java.time.*;
import java.util.*;

public final class DocumentRetentionService implements DocumentRetentionUseCases {
  private final DocumentRetentionStore store;
  private final DocumentStore documents;
  private final DocumentIdentity identity;
  private final UnitOfWork tx;
  private final Clock clock;

  public DocumentRetentionService(
      DocumentRetentionStore store,
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

  private StoredDocument document(DocumentActor a, String id) {
    return documents.find(a.tenant(), id).orElseThrow(AccessDenied::new);
  }

  private List<RetentionEvent> history(String tenant, String root) {
    var h = store.history(tenant, root);
    if (h.size() > 100)
      throw new CommandConflict("Governance capacity exceeded; contact document operations.");
    return h;
  }

  private List<RetentionReview.Hold> holds(List<RetentionEvent> h) {
    return h.stream()
        .filter(e -> e.action().equals("PLACE_HOLD"))
        .map(
            e ->
                new RetentionReview.Hold(
                    e.holdId(),
                    e.caseReference(),
                    e.actor(),
                    e.actorEmployeeId(),
                    h.stream()
                        .noneMatch(
                            r ->
                                r.action().equals("RELEASE_HOLD")
                                    && r.holdId().equals(e.holdId()))))
        .toList();
  }

  public RetentionReview review(String subject, String id) {
    var a = hr(subject);
    var d = document(a, id);
    var h = history(a.tenant(), d.view().rootId());
    var policy = h.stream().filter(e -> e.action().equals("SET_POLICY")).findFirst();
    var date = policy.map(RetentionEvent::retainUntil).orElse(null);
    var holds = holds(h);
    var today = LocalDate.now(clock);
    return new RetentionReview(
        d.view().rootId(),
        d.view().employeeId(),
        h.isEmpty() ? 0 : h.getFirst().version(),
        today,
        DocumentRetentionPolicy.state(
            date, (int) holds.stream().filter(RetentionReview.Hold::active).count(), today),
        date,
        policy.map(RetentionEvent::basis).orElse(null),
        holds,
        h,
        false);
  }

  private String text(String value) {
    return value == null ? null : value.trim();
  }

  private void shape(RetentionCommand c) {
    DocumentTemplatePolicy.text("reason", c.reason(), 500, false);
    DocumentTemplatePolicy.key(c.idempotencyKey());
    if (c.action() == null
        || !Set.of("SET_POLICY", "PLACE_HOLD", "RELEASE_HOLD").contains(c.action()))
      throw new InputValidation(Map.of("action", "Choose a retention or legal-hold action."));
    switch (c.action()) {
      case "SET_POLICY" -> {
        DocumentTemplatePolicy.text("basis", c.basis(), 1000, false);
        if (c.holdId() != null || c.caseReference() != null)
          throw new InputValidation(Map.of("action", "Policy input must not include hold fields."));
      }
      case "PLACE_HOLD" -> {
        DocumentTemplatePolicy.text("caseReference", c.caseReference(), 80, false);
        if (c.holdId() != null || c.retainUntil() != null || c.basis() != null)
          throw new InputValidation(
              Map.of("action", "Hold placement must only include its case reference."));
      }
      case "RELEASE_HOLD" -> {
        if (c.holdId() == null || !c.holdId().matches("[a-fA-F0-9-]{36}"))
          throw new InputValidation(Map.of("holdId", "Choose an active hold."));
        if (c.retainUntil() != null || c.basis() != null || c.caseReference() != null)
          throw new InputValidation(Map.of("action", "Release must only identify its hold."));
      }
      default -> throw new IllegalArgumentException();
    }
  }

  private boolean same(RetentionEvent e, String root, RetentionCommand c, int expected) {
    return e.rootId().equals(root)
        && e.version() == expected + 1
        && e.action().equals(c.action())
        && Objects.equals(e.retainUntil(), c.retainUntil())
        && Objects.equals(e.basis(), text(c.basis()))
        && Objects.equals(e.caseReference(), text(c.caseReference()))
        && (c.action().equals("PLACE_HOLD") || Objects.equals(e.holdId(), c.holdId()))
        && e.reason().equals(c.reason().trim());
  }

  public RetentionEvent command(String subject, String id, RetentionCommand c, String correlation) {
    var original = hr(subject);
    if (c == null) throw new InputValidation(Map.of("action", "Governance input required."));
    shape(c);
    int expected = DocumentRetentionPolicy.expected(c.expectedVersion());
    return tx.run(
        () -> {
          var a = hr(subject);
          if (!a.equals(original)) throw new AccessDenied();
          var d = document(a, id);
          var root = d.view().rootId();
          documents.lock(a.tenant(), d.view().employeeId());
          documents.locked(a.tenant(), root).orElseThrow(AccessDenied::new);
          var previous = store.duplicate(a.tenant(), a.subject(), c.idempotencyKey());
          if (previous.isPresent()) {
            if (!same(previous.get(), root, c, expected))
              throw new CommandConflict("Governance key was used for different input.");
            return previous.get();
          }
          var h = history(a.tenant(), root);
          DocumentRetentionPolicy.version(h.isEmpty() ? 0 : h.getFirst().version(), expected);
          String holdId = c.holdId();
          if (c.action().equals("SET_POLICY")) {
            var policy = h.stream().filter(e -> e.action().equals("SET_POLICY")).findFirst();
            DocumentRetentionPolicy.retain(
                policy.map(RetentionEvent::retainUntil).orElse(null),
                c.retainUntil(),
                LocalDate.now(clock));
          }
          if (c.action().equals("PLACE_HOLD")) {
            if (holds(h).stream()
                .anyMatch(v -> v.active() && v.caseReference().equals(c.caseReference().trim())))
              throw new CommandConflict("This case already has an active hold.");
            holdId = UUID.randomUUID().toString();
          }
          if (c.action().equals("RELEASE_HOLD")) {
            var hold = holds(h).stream().filter(v -> v.id().equals(c.holdId())).findFirst();
            DocumentRetentionPolicy.release(
                hold.isPresent(),
                hold.map(RetentionReview.Hold::active).orElse(false),
                hold.map(RetentionReview.Hold::placedEmployeeId).orElse(""),
                a.employeeId(),
                hold.map(RetentionReview.Hold::placedBy).orElse(""),
                a.subject());
          }
          var event =
              new RetentionEvent(
                  root,
                  expected + 1,
                  c.action(),
                  c.retainUntil(),
                  text(c.basis()),
                  holdId,
                  text(c.caseReference()),
                  a.subject(),
                  a.employeeId(),
                  c.reason().trim(),
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  correlation);
          store.append(a.tenant(), d.view().employeeId(), event, c.idempotencyKey());
          return event;
        });
  }
}
