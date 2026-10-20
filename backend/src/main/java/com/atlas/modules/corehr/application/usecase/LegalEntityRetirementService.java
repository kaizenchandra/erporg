package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.LegalEntityRetirementModels.*;
import com.atlas.modules.corehr.application.port.in.LegalEntityRetirementUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import com.atlas.modules.corehr.domain.model.LegalEntityRetirement.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class LegalEntityRetirementService implements LegalEntityRetirementUseCases {
  private final LegalEntityStore references;
  private final LegalEntityRetirementStore store;
  private final UnitOfWork writes, reads;
  private final Clock clock;

  public LegalEntityRetirementService(
      LegalEntityStore references,
      LegalEntityRetirementStore store,
      UnitOfWork writes,
      UnitOfWork reads,
      Clock clock) {
    this.references = references;
    this.store = store;
    this.writes = writes;
    this.reads = reads;
    this.clock = clock;
  }

  private void hr(Actor a) {
    if (!a.hr()) throw new ChangeRequest.Denied();
  }

  private long version(BigDecimal v) {
    try {
      if (v == null || v.signum() < 0) throw new ArithmeticException();
      return v.longValueExact();
    } catch (ArithmeticException e) {
      throw new ChangeRequest.Validation(
          Map.of("version", "Use an exact nonnegative integer version."));
    }
  }

  private void key(String key) {
    if (key == null || !key.matches("[a-zA-Z0-9-]{8,80}"))
      throw new ChangeRequest.Validation(
          Map.of("idempotencyKey", "A valid command key is required."));
  }

  private List<NotificationMember> notificationMembers(Actor actor) {
    var candidates = store.notificationCandidates(actor.tenant());
    if (candidates.size() > 1000)
      throw new ChangeRequest.Conflict(
          "HR notification membership exceeds the supported limit. Contact your administrator.");
    return candidates.stream()
        .filter(m -> actor.tenant().equals(m.tenant()) && "HR".equals(m.role()))
        .toList();
  }

  private Context load(Actor a, String code) {
    var ref = references.find(a.tenant(), code).orElseThrow(ChangeRequest.Denied::new);
    var states = store.history(a.tenant(), code);
    var date =
        states.stream()
            .filter(s -> s.decision() != null && s.decision().decision().equals("APPROVED"))
            .map(s -> s.request().effectiveDate())
            .findFirst()
            .orElse(null);
    return new Context(
        ref,
        states.size() + states.stream().filter(s -> s.decision() != null).count(),
        date,
        references.assignmentRecorded(a.tenant(), code),
        states);
  }

  public InboxPage inbox(Actor actor, InboxQuery query) {
    hr(actor);
    var scope =
        new InboxScope(
            actor.tenant(), actor.subject(), actor.employeeId(), query.scope().equals("OWN"));
    return reads.run(() -> store.inbox(scope, query));
  }

  public Context context(Actor a, String code) {
    hr(a);
    return reads.run(() -> load(a, code));
  }

  public Proposal submit(Actor a, String code, Submit c, String correlation) {
    hr(a);
    long expected = version(c.version());
    key(c.idempotencyKey());
    String reason = LegalEntityRetirement.text(c.reason(), "reason");
    return writes.run(
        () -> {
          references.lock(a.tenant());
          var ctx = load(a, code);
          var previous = store.duplicate(a.tenant(), a.subject(), c.idempotencyKey());
          if (previous.isPresent()) {
            var p = previous.get();
            if (!p.code().equals(code)
                || !p.requesterEmployeeId().equals(a.employeeId())
                || p.sourceVersion() != expected
                || !Objects.equals(p.effectiveDate(), c.effectiveDate())
                || !p.reason().equals(reason))
              throw new ChangeRequest.Conflict(
                  "Command key was used for another retirement proposal.");
            return p;
          }
          if (ctx.assignmentRecorded())
            throw new ChangeRequest.Conflict(
                "Approved assignment history freezes this declared reference against retirement, including after unassignment.");
          if (ctx.version() != expected)
            throw new ChangeRequest.Conflict(
                "Reference history changed. Refresh before requesting retirement.");
          if (ctx.retirementDate() != null
              || ctx.requests().stream().anyMatch(s -> s.decision() == null))
            throw new ChangeRequest.Conflict("An approved or pending retirement already exists.");
          if (ctx.requests().size() >= 20)
            throw new ChangeRequest.Conflict(
                "The retirement history limit of twenty attempts has been reached.");
          LegalEntityRetirement.dates(
              c.effectiveDate(), ctx.reference().entity().effectiveFrom(), LocalDate.now(clock));
          var recipients =
              notificationMembers(a).stream()
                  .filter(
                      m ->
                          !m.subject().equals(a.subject())
                              && !m.employeeId().equals(a.employeeId()))
                  .toList();
          var proposal =
              new Proposal(
                  UUID.randomUUID().toString(),
                  code,
                  ctx.requests().size() + 1,
                  expected,
                  c.effectiveDate(),
                  reason,
                  a.subject(),
                  a.employeeId(),
                  clock.instant().truncatedTo(ChronoUnit.MICROS),
                  correlation);
          store.insert(a.tenant(), proposal, c.idempotencyKey());
          for (var member : recipients)
            store.notify(
                a.tenant(),
                member.subject(),
                "A declared-reference retirement needs independent HR review. Open Approvals and choose Reference retirements.",
                proposal.recordedAt());
          return proposal;
        });
  }

  public Decision decide(Actor a, String code, String id, Review c, String correlation) {
    hr(a);
    long version = version(c.version());
    key(c.idempotencyKey());
    String comment = LegalEntityRetirement.text(c.comment(), "comment");
    return writes.run(
        () -> {
          references.lock(a.tenant());
          var ctx = load(a, code);
          var state =
              ctx.requests().stream()
                  .filter(s -> s.request().id().equals(id))
                  .findFirst()
                  .orElseThrow(ChangeRequest.Denied::new);
          LegalEntityRetirement.independent(state.request(), a.subject(), a.employeeId());
          var previous = store.duplicateDecision(a.tenant(), a.subject(), c.idempotencyKey());
          if (previous.isPresent()) {
            var p = previous.get();
            if (!p.requestId().equals(id)
                || !p.actorEmployeeId().equals(a.employeeId())
                || !Objects.equals(p.decision(), c.decision())
                || !p.comment().equals(comment)
                || version != 0)
              throw new ChangeRequest.Conflict("Decision key was used for different input.");
            return p;
          }
          if (version != 0 || state.decision() != null)
            throw new ChangeRequest.Conflict(
                "The proposal has already changed. Refresh its history.");
          LegalEntityRetirement.decision(
              c.decision(), comment, state.request().effectiveDate(), LocalDate.now(clock));
          var recipients =
              notificationMembers(a).stream()
                  .filter(
                      m ->
                          m.subject().equals(state.request().requestedBy())
                              && m.employeeId().equals(state.request().requesterEmployeeId()))
                  .toList();
          var decision =
              new Decision(
                  id,
                  c.decision(),
                  comment,
                  a.subject(),
                  a.employeeId(),
                  clock.instant().truncatedTo(ChronoUnit.MICROS),
                  correlation);
          store.insertDecision(a.tenant(), code, decision, c.idempotencyKey());
          for (var member : recipients)
            store.notify(
                a.tenant(),
                member.subject(),
                decision.decision().equals("APPROVED")
                    ? "Your declared-reference retirement was approved. Open Settings for its effective date and history."
                    : "Your declared-reference retirement was rejected. Open Settings for its decision history.",
                decision.recordedAt());
          return decision;
        });
  }
}
