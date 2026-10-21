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

final class PlanningWorkflows extends TalentSupport {
  PlanningWorkflows(Store s, Environment e, Transactions r, Transactions w, Clock c) {
    super(s, e, r, w, c);
  }

  public Compensation compensation(String s, CompensationInput c, String corr) {
    hr(env.self(s));
    return command(
        s,
        "compensation",
        null,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Compensation.class,
        a -> {
          hr(a);
          require(
              c.proposals() != null && c.proposals().size() <= 50,
              "proposals",
              "Enter 1–50 proposals.");
          var list = new ArrayList<Proposal>();
          for (var p : c.proposals()) {
            require(p != null, "proposals", "Each proposal must be an object.");
            eligible(a, p.employeeId());
            require(
                p.effectiveDate() != null && !p.effectiveDate().isBefore(today()),
                "effectiveDate",
                "Use today or a future effective date.");
            var current = env.currentSalary(s, p.employeeId(), p.effectiveDate(), c.currency());
            require(
                p.currentSalary() == null
                    || money(p.currentSalary(), "currentSalary").compareTo(current) == 0,
                "currentSalary",
                "Authoritative salary changed. Refresh the proposal.");
            list.add(
                new Proposal(
                    p.employeeId(),
                    current,
                    money(p.proposedSalary(), "proposedSalary"),
                    money(p.bonus(), "bonus"),
                    p.effectiveDate()));
          }
          var budget = money(c.budget(), "budget");
          return new Compensation(
              stamp(a, "DRAFT"),
              text(c.title(), "title", 180),
              currency(c.currency()),
              budget,
              List.copyOf(list),
              TalentPolicy.compensation(list, budget),
              "");
        });
  }

  public Compensation compensationAction(String s, String id, Decision c, String corr) {
    text(c.action(), "action", 40);
    hr(env.self(s));
    return command(
        s,
        "compensation",
        id,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Compensation.class,
        a -> {
          hr(a);
          var p = load(a, "compensation", id, Compensation.class);
          revision(p.meta().version(), c.version());
          var status = p.meta().status();
          var intake = p.payrollIntakeId();
          switch (c.action()) {
            case "SUBMIT" -> {
              state(status, "DRAFT", "RETURNED");
              status = "SUBMITTED";
            }
            case "APPROVE", "RETURN" -> {
              state(status, "SUBMITTED");
              independent(p.meta(), a.subject(), a.employeeId());
              status = c.action().equals("APPROVE") ? "APPROVED" : "RETURNED";
              if (status.equals("APPROVED"))
                intake = env.compensationIntake(s, p, c.reason(), corr);
            }
            default ->
                throw new InputValidation(Map.of("action", "Choose SUBMIT, APPROVE or RETURN."));
          }
          return new Compensation(
              p.meta().next(status, now()),
              p.title(),
              p.currency(),
              p.budget(),
              p.proposals(),
              p.total(),
              intake);
        });
  }

  public Succession succession(String s, SuccessionInput c, String corr) {
    hr(env.self(s));
    return command(
        s,
        "succession",
        null,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Succession.class,
        a -> {
          hr(a);
          require(
              c.nominees() != null && !c.nominees().isEmpty() && c.nominees().size() <= 30,
              "nominees",
              "Enter 1–30 nominees.");
          var items = new ArrayList<Nominee>();
          var ids = new HashSet<String>();
          for (var n : c.nominees()) {
            require(n != null, "nominees", "Each nominee must be an object.");
            eligible(a, n.employeeId());
            require(ids.add(n.employeeId()), "employeeId", "Each nominee must be unique.");
            require(
                Set.of("READY_NOW", "WITHIN_ONE_YEAR", "ONE_TO_THREE_YEARS")
                    .contains(n.readiness() == null ? "" : n.readiness()),
                "readiness",
                "Select a readiness category.");
            items.add(
                new Nominee(
                    n.employeeId(),
                    text(n.skills(), "skills", 1000),
                    n.readiness(),
                    text(n.assessment(), "assessment", 2000)));
          }
          return new Succession(
              stamp(a, "ACTIVE"), text(c.position(), "position", 180), List.copyOf(items));
        });
  }

  public Succession successionUpdate(String s, String id, SuccessionUpdate c, String corr) {
    hr(env.self(s));
    return command(
        s,
        "succession",
        id,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Succession.class,
        a -> {
          hr(a);
          var plan = load(a, "succession", id, Succession.class);
          revision(plan.meta().version(), c.version());
          eligible(a, c.employeeId());
          require(
              Set.of("READY_NOW", "WITHIN_ONE_YEAR", "ONE_TO_THREE_YEARS")
                  .contains(c.readiness() == null ? "" : c.readiness()),
              "readiness",
              "Select a readiness category.");
          var items = new ArrayList<>(plan.nominees());
          var nominee =
              new Nominee(
                  c.employeeId(),
                  text(c.skills(), "skills", 1000),
                  c.readiness(),
                  text(c.assessment(), "assessment", 2000));
          boolean found = false;
          for (int i = 0; i < items.size(); i++)
            if (items.get(i).employeeId().equals(c.employeeId())) {
              items.set(i, nominee);
              found = true;
            }
          if (!found) {
            require(items.size() < 30, "nominees", "Use at most 30 nominees.");
            items.add(nominee);
          }
          return new Succession(
              plan.meta().next("ACTIVE", now()), plan.position(), List.copyOf(items));
        });
  }

  public Scenario scenario(String s, ScenarioInput c, String corr) {
    hr(env.self(s));
    return command(
        s,
        "scenarios",
        null,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Scenario.class,
        a -> {
          hr(a);
          require(
              c.asOf() != null && c.asOf().equals(today()),
              "asOf",
              "Snapshot current worker data using today's UTC date.");
          require(
              c.workerIds() != null
                  && c.workerIds().size() <= 200
                  && new HashSet<>(c.workerIds()).size() == c.workerIds().size(),
              "workerIds",
              "Select up to 200 unique current workers.");
          for (var worker : c.workerIds()) eligible(a, worker);
          require(c.lines() != null && c.lines().size() <= 50, "lines", "Enter 1–50 dimensions.");
          var lines = new ArrayList<CostLine>();
          for (var l : c.lines()) {
            require(l != null, "lines", "Each scenario line must be an object.");
            require(
                l.baselineHeadcount() != null && l.proposedHeadcount() != null,
                "headcount",
                "Enter baseline and proposed headcount.");
            lines.add(
                new CostLine(
                    text(l.dimension(), "dimension", 100),
                    l.baselineHeadcount(),
                    l.proposedHeadcount(),
                    money(l.annualUnitCost(), "annualUnitCost")));
          }
          return TalentPolicy.scenario(
              stamp(a, "SAVED"),
              text(c.title(), "title", 180),
              currency(c.currency()),
              c.asOf(),
              c.workerIds(),
              lines);
        });
  }
}
