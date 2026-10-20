package com.atlas.modules.talent.application.usecase;

import static com.atlas.modules.talent.domain.model.TalentPolicy.*;

import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.application.port.out.TalentPorts.*;
import com.atlas.modules.talent.domain.model.TalentModels.*;
import com.atlas.modules.talent.domain.model.TalentPolicy;
import com.atlas.shared.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;

final class RecruitingWorkflows extends TalentSupport {
  RecruitingWorkflows(Store s, Environment e, Transactions r, Transactions w, Clock c) {
    super(s, e, r, w, c);
  }

  public Requisition requisition(String s, RequisitionInput c, String corr) {
    hr(env.self(s));
    return command(
        s,
        "requisitions",
        null,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Requisition.class,
        a -> {
          hr(a);
          var title = text(c.title(), "title", 120);
          text(c.department(), "department", 100);
          text(c.location(), "location", 100);
          var m = eligible(a, c.managerId());
          require(
              c.start() != null && !c.start().isBefore(today()),
              "start",
              "Choose today or a future start date.");
          return new Requisition(
              stamp(a, "DRAFT"), title, c.department(), c.location(), m.id(), c.start(), List.of());
        });
  }

  public Requisition requisitionAction(String s, String id, Decision c, String corr) {
    text(c.action(), "action", 40);
    hr(env.self(s));
    return command(
        s,
        "requisitions",
        id,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Requisition.class,
        a -> {
          hr(a);
          var r = load(a, "requisitions", id, Requisition.class);
          revision(r.meta().version(), c.version());
          if (Set.of("APPROVE", "RETURN").contains(c.action()))
            independent(r.meta(), a.subject(), a.employeeId());
          return new Requisition(
              r.meta().next(TalentPolicy.requisition(r.meta().status(), c.action()), now()),
              r.title(),
              r.department(),
              r.location(),
              r.managerId(),
              r.start(),
              r.candidates());
        });
  }

  public Requisition candidate(String s, String id, CandidateInput c, String corr) {
    text(c.action(), "action", 40);
    hr(env.self(s));
    return command(
        s,
        "requisitions",
        id,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Requisition.class,
        a -> {
          hr(a);
          var r = load(a, "requisitions", id, Requisition.class);
          revision(r.meta().version(), c.version());
          state(r.meta().status(), "OPEN");
          var list = new ArrayList<>(r.candidates());
          if ("ADD".equals(c.action())) {
            require(
                list.size() < 50,
                "candidates",
                "A requisition supports at most 50 candidates; open another requisition if needed.");
            var name = text(c.name(), "name", 120);
            var email = text(c.email(), "email", 180).toLowerCase(Locale.ROOT);
            require(
                email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                    && list.stream().noneMatch(x -> x.email().equals(email)),
                "email",
                "Use a unique valid candidate email for this requisition.");
            list.add(
                new Candidate(
                    id(),
                    name,
                    email,
                    text(c.phone(), "phone", 40),
                    "APPLIED",
                    "",
                    "",
                    "",
                    "",
                    ""));
          } else {
            var i = -1;
            for (int n = 0; n < list.size(); n++)
              if (list.get(n).id().equals(c.candidateId())) i = n;
            require(i >= 0, "candidateId", "Select a candidate from this requisition.");
            var old = list.get(i);
            Candidate next;
            if ("SEND_HIRE".equals(c.action())) {
              state(old.stage(), "ACCEPTED");
              var h =
                  env.submitHire(
                      s,
                      new Environment.HireInput(
                          old.name(),
                          old.email(),
                          old.phone(),
                          r.title(),
                          r.department(),
                          r.location(),
                          r.managerId(),
                          r.start()),
                      c.reason(),
                      c.idempotencyKey(),
                      corr);
              next =
                  new Candidate(
                      old.id(),
                      old.name(),
                      old.email(),
                      old.phone(),
                      "HIRE_SUBMITTED",
                      old.interviewAt(),
                      old.interviewNotes(),
                      old.offer(),
                      h.id(),
                      "");
            } else if ("APPROVE_HIRE".equals(c.action()) || "REJECT_HIRE".equals(c.action())) {
              state(old.stage(), "HIRE_SUBMITTED");
              var h =
                  env.decideHire(
                      s,
                      old.hireId(),
                      c.action().equals("APPROVE_HIRE") ? "APPROVED" : "REJECTED",
                      c.reason(),
                      corr);
              var stage = h.status().equals("APPROVED") ? "HIRED" : "REJECTED";
              next =
                  new Candidate(
                      old.id(),
                      old.name(),
                      old.email(),
                      old.phone(),
                      stage,
                      old.interviewAt(),
                      old.interviewNotes(),
                      old.offer(),
                      h.id(),
                      h.employeeId() == null ? "" : h.employeeId());
              if (stage.equals("HIRED")) {
                var plan = new Onboarding(stamp(a, "OPEN"), h.id(), h.employeeId(), "", List.of());
                db.insert(a.tenant(), "onboarding", plan.meta().id(), plan);
                db.audit(
                    a.tenant(),
                    new Audit(
                        id(),
                        "onboarding",
                        plan.meta().id(),
                        a.subject(),
                        a.employeeId(),
                        "HIRE_APPROVED",
                        c.reason(),
                        "",
                        db.encode(plan),
                        now(),
                        corr));
              }
            } else {
              TalentPolicy.candidate(old.stage(), c.action());
              var at = old.interviewAt();
              var notes = old.interviewNotes();
              var offer = old.offer();
              if (c.action().equals("INTERVIEW")) {
                text(c.interviewAt(), "interviewAt", 80);
                try {
                  OffsetDateTime.parse(c.interviewAt());
                } catch (java.time.format.DateTimeParseException e) {
                  throw new InputValidation(
                      Map.of("interviewAt", "Use an ISO date/time with an explicit UTC offset."));
                }
                at = c.interviewAt();
                notes = text(c.notes(), "notes", 2000);
              }
              if (c.action().equals("OFFER")) offer = text(c.offer(), "offer", 2000);
              next =
                  new Candidate(
                      old.id(),
                      old.name(),
                      old.email(),
                      old.phone(),
                      c.action(),
                      at,
                      notes,
                      offer,
                      old.hireId(),
                      old.employeeId());
            }
            list.set(i, next);
          }
          return new Requisition(
              r.meta().next(r.meta().status(), now()),
              r.title(),
              r.department(),
              r.location(),
              r.managerId(),
              r.start(),
              List.copyOf(list));
        });
  }

  public Onboarding joining(String s, String id, Joining c, String corr) {
    text(c.action(), "action", 40);
    return command(
        s,
        "onboarding",
        id,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Onboarding.class,
        a -> {
          var p = load(a, "onboarding", id, Onboarding.class);
          revision(p.meta().version(), c.version());
          state(p.meta().status(), "OPEN");
          var form = p.joiningForm();
          var tasks = new ArrayList<>(p.tasks());
          var status = "OPEN";
          switch (c.action()) {
            case "FORM" -> {
              if (!a.role().equals("HR") && !a.employeeId().equals(p.employeeId()))
                throw new AccessDenied();
              form = text(c.joiningForm(), "joiningForm", 3000);
            }
            case "ADD_TASKS" -> {
              hr(a);
              require(
                  c.tasks() != null
                      && !c.tasks().isEmpty()
                      && tasks.size() + c.tasks().size() <= 30,
                  "tasks",
                  "Add 1–30 tasks in dependency order.");
              for (var t : c.tasks()) {
                require(t != null, "tasks", "Each joining task must be an object.");
                text(t.title(), "title", 180);
                eligible(a, t.assignee());
                require(
                    t.assignee().equals(p.employeeId()) || env.hasRole(s, t.assignee(), "HR"),
                    "assignee",
                    "Joining tasks can be assigned to the new employee or an HR coordinator.");
                require(
                    t.dependsOn() != null
                        && t.dependsOn().size() <= 30
                        && new HashSet<>(t.dependsOn()).size() == t.dependsOn().size(),
                    "dependsOn",
                    "Use unique existing task IDs.");
                for (var dep : t.dependsOn())
                  require(
                      tasks.stream().anyMatch(x -> x.id().equals(dep)),
                      "dependsOn",
                      "Dependencies must refer to existing earlier tasks.");
                tasks.add(
                    new Task(
                        id(),
                        t.title(),
                        t.assignee(),
                        List.copyOf(t.dependsOn()),
                        false,
                        "",
                        null,
                        ""));
              }
            }
            case "COMPLETE_TASK" -> {
              int i = -1;
              for (int n = 0; n < tasks.size(); n++)
                if (tasks.get(n).id().equals(c.taskId())) i = n;
              require(i >= 0, "taskId", "Select an assigned task.");
              var t = tasks.get(i);
              if (!a.employeeId().equals(t.assignee())) throw new AccessDenied();
              require(!t.complete(), "taskId", "This task is already complete.");
              dependencies(tasks, t);
              tasks.set(
                  i,
                  new Task(
                      t.id(),
                      t.title(),
                      t.assignee(),
                      t.dependsOn(),
                      true,
                      a.subject(),
                      now(),
                      text(c.evidence(), "evidence", 2000)));
            }
            case "ACTIVATE" -> {
              hr(a);
              require(
                  !form.isBlank() && !tasks.isEmpty() && tasks.stream().allMatch(Task::complete),
                  "tasks",
                  "Complete the joining form and all dependent tasks before activation.");
              env.completeOnboarding(
                  s,
                  p.hireId(),
                  version(c.employeeVersion()),
                  c.reason(),
                  c.idempotencyKey(),
                  corr);
              status = "COMPLETED";
            }
            default ->
                throw new InputValidation(Map.of("action", "Choose a supported joining action."));
          }
          return new Onboarding(
              p.meta().next(status, now()), p.hireId(), p.employeeId(), form, List.copyOf(tasks));
        });
  }
}
