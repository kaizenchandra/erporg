package com.atlas.modules.talent.application.usecase;

import static com.atlas.modules.talent.domain.model.TalentPolicy.*;

import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.application.port.out.TalentPorts.*;
import com.atlas.modules.talent.domain.model.TalentModels.*;
import com.atlas.shared.domain.*;
import java.time.*;
import java.util.*;

/**
 * Delegation routes responsibility to an already-authorized HR reviewer. It never grants access.
 */
final class ApprovalWorkflows extends TalentSupport {
  ApprovalWorkflows(Store d, Environment e, Transactions r, Transactions w, Clock c) {
    super(d, e, r, w, c);
  }

  private Object eligibleApproval(Actor a, String kind, String id) {
    require(
        Set.of("requisitions", "reviews", "compensation").contains(kind == null ? "" : kind),
        "kind",
        "Choose a supported approval workflow.");
    var r = load(a, kind, id, type(kind));
    var m = meta(r);
    if (!m.status().equals("SUBMITTED"))
      throw new CommandConflict("This record is no longer pending approval.");
    switch (r) {
      case Review v -> {
        manager(a, v.employeeId());
        if (a.employeeId().equals(v.employeeId())) throw new AccessDenied();
      }
      default -> {
        hr(a);
        if (a.subject().equals(m.preparedBy()) || a.employeeId().equals(m.preparedEmployee()))
          throw new AccessDenied();
      }
    }
    return r;
  }

  private boolean originalStillEligible(Actor a, Delegation d, Object r) {
    if (env.hrReviewers(a.subject()).contains(d.originalEmployee())) return true;
    if (r instanceof Review review) {
      var worker = env.worker(a.subject(), review.employeeId());
      return d.originalEmployee().equals(worker.managerId())
          && env.hasRole(a.subject(), d.originalEmployee(), "MANAGER");
    }
    return false;
  }

  public List<ResponsibilityHistory> delegationHistory(String s, String k, String id, int page) {
    require(page >= 0 && page <= 10000, "page", "Use a bounded history page.");
    return reads.run(
        () -> {
          var a = env.self(s);
          load(a, k, id, type(k));
          return db.delegationHistory(a.tenant(), k, id, page);
        });
  }

  public List<ApprovalTask> approvalInbox(String s, int page) {
    require(page >= 0 && page <= 10000, "page", "Use a bounded approval page.");
    return reads.run(
        () -> {
          var a = env.self(s);
          var tasks = new ArrayList<ApprovalTask>();
          for (var p : db.approvalCandidates(a.tenant(), page)) {
            try {
              var r = eligibleApproval(a, p.kind(), p.id());
              var m = meta(r);
              var d =
                  db.activeDelegation(a.tenant(), p.kind(), p.id(), m.version(), today())
                      .orElse(null);
              if (d != null
                  && (!env.hrReviewers(s).contains(d.delegateEmployee())
                      || !originalStillEligible(a, d, r))) d = null;
              var title =
                  switch (r) {
                    case Review v -> v.cycle() + " · " + v.employeeId();
                    case Requisition v -> v.title();
                    case Compensation v -> v.title();
                    default -> throw new IllegalStateException();
                  };
              tasks.add(new ApprovalTask(p.kind(), p.id(), title, m.version(), d));
            } catch (AccessDenied | CommandConflict ignored) {
              /* Never expose records outside current review authority. */
            }
          }
          return List.copyOf(tasks);
        });
  }

  public List<String> approvalDelegates(String s) {
    return reads.run(
        () -> {
          var a = env.self(s);
          if (a.role().equals("EMPLOYEE")) throw new AccessDenied();
          return env.hrReviewers(s).stream().filter(e -> !e.equals(a.employeeId())).toList();
        });
  }

  public Delegation delegate(String s, Delegate c, String corr) {
    text(c.reason(), "reason", 1000);
    require(
        c.idempotencyKey() != null && c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"),
        "idempotencyKey",
        "Supply a stable command key.");
    return writes.run(
        () -> {
          var a = env.self(s);
          db.lock(a.tenant());
          if (!a.equals(env.self(s))) throw new AccessDenied();
          var old = db.delegationReplay(a.tenant(), s, c.idempotencyKey());
          if (old.isPresent()) {
            var d = old.get();
            load(a, d.kind(), d.recordId(), type(d.kind()));
            if (!d.kind().equals(c.kind())
                || !d.recordId().equals(c.recordId())
                || d.recordVersion() != version(c.version())
                || !d.delegateEmployee().equals(c.delegateEmployee())
                || !d.from().equals(c.from())
                || !d.through().equals(c.through())
                || !d.reason().equals(c.reason().strip()))
              throw new CommandConflict("Delegation key was already used for different data.");
            return d;
          }
          var r = eligibleApproval(a, c.kind(), c.recordId());
          var m = meta(r);
          revision(m.version(), c.version());
          require(
              c.from() != null
                  && c.through() != null
                  && !c.from().isBefore(today())
                  && !c.through().isBefore(c.from())
                  && !c.through().isAfter(c.from().plusYears(1)),
              "through",
              "Choose a non-past responsibility window no longer than one year.");
          require(
              env.hrReviewers(s).contains(c.delegateEmployee())
                  && !c.delegateEmployee().equals(a.employeeId()),
              "delegateEmployee",
              "Choose a different, currently authorized HR reviewer.");
          if (!(r instanceof Review))
            require(
                !c.delegateEmployee().equals(m.preparedEmployee()),
                "delegateEmployee",
                "The preparer cannot receive their own approval responsibility.");
          if (r instanceof Review review)
            require(
                !review.employeeId().equals(c.delegateEmployee()),
                "delegateEmployee",
                "Workers cannot review their own submission.");
          if (db.delegationOverlap(
              a.tenant(), c.kind(), m.id(), m.version(), c.from(), c.through()))
            throw new CommandConflict(
                "An overlapping responsibility assignment exists. Revoke it before changing the reviewer.");
          var d =
              new Delegation(
                  id(),
                  c.kind(),
                  m.id(),
                  m.version(),
                  a.employeeId(),
                  c.delegateEmployee(),
                  c.from(),
                  c.through(),
                  s,
                  c.reason().strip(),
                  c.idempotencyKey(),
                  now(),
                  corr);
          db.delegate(a.tenant(), d);
          return d;
        });
  }

  public DelegationRevocation revokeDelegation(
      String s, String id, RevokeDelegation c, String corr) {
    text(c.reason(), "reason", 1000);
    require(
        c.idempotencyKey() != null && c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"),
        "idempotencyKey",
        "Supply a stable command key.");
    return writes.run(
        () -> {
          var a = env.self(s);
          db.lock(a.tenant());
          if (!a.equals(env.self(s))) throw new AccessDenied();
          var d = db.delegation(a.tenant(), id).orElseThrow(AccessDenied::new);
          if (!a.role().equals("HR") && !a.employeeId().equals(d.originalEmployee()))
            throw new AccessDenied();
          load(a, d.kind(), d.recordId(), type(d.kind()));
          var old = db.revocation(a.tenant(), id);
          if (old.isPresent()) {
            var v = old.get();
            if (v.actor().equals(s)
                && v.commandKey().equals(c.idempotencyKey())
                && v.reason().equals(c.reason().strip())) return v;
            throw new CommandConflict("Responsibility assignment was already revoked.");
          }
          var v =
              new DelegationRevocation(
                  id, s, a.employeeId(), c.reason().strip(), c.idempotencyKey(), now(), corr);
          db.revokeDelegation(a.tenant(), v);
          return v;
        });
  }
}
