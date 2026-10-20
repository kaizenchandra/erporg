package com.atlas.modules.talent.domain.model;

import static com.atlas.modules.talent.domain.model.TalentModels.*;

import com.atlas.shared.domain.*;
import java.math.*;
import java.util.*;

public final class TalentPolicy {
  private TalentPolicy() {}

  public static void require(boolean b, String field, String message) {
    if (!b) throw new InputValidation(Map.of(field, message));
  }

  public static String text(String s, String field, int limit) {
    require(
        s != null
            && !s.isBlank()
            && s.length() <= limit
            && s.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n'),
        field,
        "Enter 1–" + limit + " characters.");
    return s.strip();
  }

  public static BigDecimal money(String s, String field) {
    try {
      var m = new BigDecimal(s);
      require(
          m.signum() >= 0 && m.scale() <= 2 && m.precision() - m.scale() <= 12,
          field,
          "Use a nonnegative amount with at most two decimal places and 12 whole digits.");
      return m.setScale(2);
    } catch (NumberFormatException | NullPointerException e) {
      throw new InputValidation(Map.of(field, "Enter an exact decimal amount."));
    }
  }

  public static void independent(Stamp s, String subject, String employee) {
    if (s.preparedBy().equals(subject) || s.preparedEmployee().equals(employee))
      throw new AccessDenied();
  }

  public static void state(String current, String... expected) {
    if (!Set.of(expected).contains(current))
      throw new CommandConflict(
          "This action is not allowed in state " + current + ". Reload the record.");
  }

  public static String requisition(String current, String action) {
    return switch (action) {
      case "SUBMIT" -> {
        state(current, "DRAFT", "RETURNED");
        yield "SUBMITTED";
      }
      case "APPROVE" -> {
        state(current, "SUBMITTED");
        yield "OPEN";
      }
      case "RETURN" -> {
        state(current, "SUBMITTED");
        yield "RETURNED";
      }
      case "CLOSE" -> {
        state(current, "OPEN");
        yield "CLOSED";
      }
      default ->
          throw new InputValidation(Map.of("action", "Choose a supported requisition action."));
    };
  }

  public static void candidate(String stage, String next) {
    boolean ok =
        switch (stage) {
          case "APPLIED" -> Set.of("INTERVIEW", "REJECTED").contains(next);
          case "INTERVIEW" -> Set.of("OFFER", "REJECTED").contains(next);
          case "OFFER" -> Set.of("ACCEPTED", "REJECTED").contains(next);
          default -> false;
        };
    require(
        ok, "stage", "Move candidates through applied, interview, offer and accepted, or reject.");
  }

  public static void dependencies(List<Task> tasks, Task target) {
    for (String id : target.dependsOn())
      require(
          tasks.stream().anyMatch(t -> t.id().equals(id) && t.complete()),
          "dependsOn",
          "Complete prerequisite tasks first.");
  }

  public static BigDecimal compensation(List<Proposal> items, BigDecimal budget) {
    require(!items.isEmpty() && items.size() <= 50, "proposals", "Enter 1–50 proposals.");
    var ids = new HashSet<String>();
    var total = BigDecimal.ZERO.setScale(2);
    for (var p : items) {
      require(ids.add(p.employeeId()), "employeeId", "Each employee may appear only once.");
      require(
          p.proposedSalary().compareTo(p.currentSalary()) >= 0,
          "proposedSalary",
          "This planning workflow supports increases; decreases require a separate employment-change policy.");
      total = total.add(p.proposedSalary().subtract(p.currentSalary())).add(p.bonus());
    }
    require(
        total.compareTo(budget) <= 0,
        "budget",
        "Salary increases plus bonuses exceed the approved planning budget.");
    return total;
  }

  public static Scenario scenario(
      Stamp meta,
      String title,
      String currency,
      java.time.LocalDate asOf,
      List<String> workers,
      List<CostLine> lines) {
    require(!lines.isEmpty() && lines.size() <= 50, "lines", "Enter 1–50 scenario dimensions.");
    int base = 0, next = 0;
    var bc = BigDecimal.ZERO.setScale(2);
    var nc = bc;
    var names = new HashSet<String>();
    for (var l : lines) {
      require(names.add(l.dimension()), "dimension", "Dimensions must be unique.");
      require(
          l.baselineHeadcount() >= 0
              && l.proposedHeadcount() >= 0
              && l.baselineHeadcount() <= 100000
              && l.proposedHeadcount() <= 100000,
          "headcount",
          "Use headcount 0–100000 per dimension.");
      base += l.baselineHeadcount();
      next += l.proposedHeadcount();
      bc = bc.add(l.annualUnitCost().multiply(BigDecimal.valueOf(l.baselineHeadcount())));
      nc = nc.add(l.annualUnitCost().multiply(BigDecimal.valueOf(l.proposedHeadcount())));
    }
    require(
        base == workers.size(),
        "baselineHeadcount",
        "Baseline headcount must match the selected current-worker snapshot.");
    return new Scenario(
        meta,
        title,
        currency,
        asOf,
        List.copyOf(workers),
        List.copyOf(lines),
        base,
        next,
        bc,
        nc,
        nc.subtract(bc));
  }
}
