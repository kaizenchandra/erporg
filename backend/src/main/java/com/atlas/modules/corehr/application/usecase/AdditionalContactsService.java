package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.ContactModels.*;
import com.atlas.modules.corehr.application.port.in.AdditionalContactsUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class AdditionalContactsService implements AdditionalContactsUseCases {
  private final AdditionalContactsStore store;
  private final PeopleStore people;
  private final UnitOfWork writes, reads;
  private final Clock clock;

  public AdditionalContactsService(
      AdditionalContactsStore store,
      PeopleStore people,
      UnitOfWork writes,
      UnitOfWork reads,
      Clock clock) {
    this.store = store;
    this.people = people;
    this.writes = writes;
    this.reads = reads;
    this.clock = clock;
  }

  private Actor access(String subject, String employeeId) {
    var a = people.actor(subject, LocalDate.now(clock));
    if (!a.hr() && !a.employeeId().equals(employeeId)) throw new ChangeRequest.Denied();
    return a;
  }

  private long version(BigDecimal value) {
    try {
      if (value != null && value.signum() >= 0) return value.longValueExact();
    } catch (ArithmeticException ignored) {
    }
    throw new IllegalArgumentException("An exact nonnegative version is required.");
  }

  private void shape(String reason, String key) {
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw new ChangeRequest.Validation(
          Map.of("reason", "A reason of 1–500 characters is required."));
    if (key == null || !key.matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException("A valid idempotency key is required.");
  }

  public Page inbox(String subject, String scope, Query query) {
    return reads.run(
        () -> {
          var actor = people.actor(subject, LocalDate.now(clock));
          AdditionalContactsStore.InboxScope permitted;
          if ("OWN".equals(scope)) {
            permitted =
                new AdditionalContactsStore.InboxScope(actor.tenant(), actor.employeeId(), "", "");
          } else if ("REVIEW".equals(scope)) {
            if (!actor.hr()) throw new ChangeRequest.Denied();
            permitted =
                new AdditionalContactsStore.InboxScope(
                    actor.tenant(), "", actor.employeeId(), actor.subject());
          } else throw new IllegalArgumentException("Use OWN or REVIEW scope.");
          return store.inbox(permitted, query);
        });
  }

  public Context context(String subject, String employeeId, Query query) {
    return reads.run(
        () -> {
          var a = access(subject, employeeId);
          var e = people.employee(a, employeeId, LocalDate.now(clock), false);
          var current = store.current(a.tenant(), employeeId, LocalDate.now(clock));
          return new Context(
              employeeId,
              e.version(),
              current.contacts(),
              current.effectiveDate(),
              store.page(a.tenant(), employeeId, query));
        });
  }

  public ContactRequest submit(String subject, String employeeId, Submit c, String correlation) {
    return writes.run(
        () -> {
          var a = access(subject, employeeId);
          if (c.contacts() == null
              || c.contacts().size() > 4
              || c.contacts().stream().anyMatch(Objects::isNull))
            throw new ChangeRequest.Validation(
                Map.of("contacts", "Provide zero to four additional contacts."));
          var contacts =
              EmergencyContact.snapshot(
                  c.contacts().stream()
                      .map(i -> new EmergencyContact(i.name(), i.relationship(), i.phone()))
                      .toList());
          long v = version(c.employeeVersion());
          shape(c.reason(), c.idempotencyKey());
          if (c.effectiveDate() == null)
            throw new ChangeRequest.Validation(
                Map.of("effectiveDate", "Choose an effective date."));
          var e = people.employee(a, employeeId, LocalDate.now(clock), true);
          var replay = store.duplicate(a.tenant(), subject, c.idempotencyKey(), false);
          if (replay.isPresent()) {
            var r = replay.get();
            if (!r.employeeId().equals(employeeId)
                || !r.contacts().equals(contacts)
                || !r.effectiveDate().equals(c.effectiveDate())
                || !r.reason().equals(c.reason().trim())
                || r.employeeVersion() != v)
              throw new ChangeRequest.Conflict("Key was used for another contact request.");
            return r;
          }
          ChangeRequest.validateDate(c.effectiveDate(), LocalDate.now(clock));
          if (e.status().equals("Ended")
              || people.employee(a, employeeId, c.effectiveDate(), false).status().equals("Ended"))
            throw new ChangeRequest.Conflict("Employment has ended. Contact HR.");
          if (e.version() != v)
            throw new ChangeRequest.Conflict(
                "Employee changed. Refresh contacts before submitting.");
          if (store.pending(a.tenant(), employeeId))
            throw new ChangeRequest.Conflict(
                "Review the pending contact request before submitting another.");
          var current = store.current(a.tenant(), employeeId, c.effectiveDate());
          if (current.contacts().equals(contacts))
            throw new ChangeRequest.Validation(
                Map.of("contacts", "Change the additional contacts before submitting."));
          var r =
              new ContactRequest(
                  UUID.randomUUID().toString(),
                  employeeId,
                  current.contacts(),
                  contacts,
                  c.effectiveDate(),
                  c.reason().trim(),
                  "SUBMITTED",
                  subject,
                  a.employeeId(),
                  v,
                  clock.instant().truncatedTo(ChronoUnit.MICROS),
                  correlation,
                  0,
                  null,
                  null,
                  null,
                  null);
          store.insert(a.tenant(), r, c.idempotencyKey());
          return r;
        });
  }

  public ContactRequest review(
      String subject, String employeeId, String id, Review c, String correlation) {
    return writes.run(
        () -> {
          var a = access(subject, employeeId);
          if (!a.hr()) throw new ChangeRequest.Denied();
          long v = version(c.version());
          shape(c.comment(), c.idempotencyKey());
          if (v > Integer.MAX_VALUE)
            throw new IllegalArgumentException("A supported request version is required.");
          var e = people.employee(a, employeeId, LocalDate.now(clock), true);
          var r = store.get(a.tenant(), employeeId, id, true);
          var replay = store.duplicate(a.tenant(), subject, c.idempotencyKey(), true);
          if (replay.isPresent()) {
            var old = replay.get();
            if (!old.id().equals(id)
                || !old.status().equals(c.decision())
                || !old.comment().equals(c.comment().trim())
                || v != 0)
              throw new ChangeRequest.Conflict("Key was used for another contact decision.");
            return old;
          }
          r.requireReviewer(a, c.decision(), (int) v);
          if (c.decision().equals("APPROVED")
              && (e.version() != r.employeeVersion()
                  || e.status().equals("Ended")
                  || people
                      .employee(a, employeeId, r.effectiveDate(), false)
                      .status()
                      .equals("Ended")))
            throw new ChangeRequest.Conflict(
                "Employee changed or ended. Reject and submit a fresh contact request.");
          var decided =
              new ContactRequest(
                  r.id(),
                  employeeId,
                  r.oldContacts(),
                  r.contacts(),
                  r.effectiveDate(),
                  r.reason(),
                  c.decision(),
                  r.requestedBy(),
                  r.requesterEmployeeId(),
                  r.employeeVersion(),
                  r.recordedAt(),
                  r.correlationId(),
                  1,
                  subject,
                  c.comment().trim(),
                  clock.instant().truncatedTo(ChronoUnit.MICROS),
                  correlation);
          store.decide(a.tenant(), decided, a.employeeId(), e.version(), c.idempotencyKey());
          return decided;
        });
  }
}
