package com.atlas.modules.benefits.application.usecase;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.in.BenefitsUseCases;
import com.atlas.modules.benefits.application.port.out.*;
import com.atlas.modules.benefits.domain.model.BenefitPlan;
import com.atlas.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class BenefitsService implements BenefitsUseCases {
  private final PlanStore store;
  private final WorkforcePort workforce;
  private final UnitOfWork tx;
  private final UnitOfWork reads;
  private final Clock clock;

  public BenefitsService(
      PlanStore store, WorkforcePort workforce, UnitOfWork tx, UnitOfWork reads, Clock clock) {
    this.store = store;
    this.workforce = workforce;
    this.tx = tx;
    this.reads = reads;
    this.clock = clock;
  }

  private PlanView view(BenefitPlan p, BenefitContext context, LocalDate evaluatedOn) {
    var eligibility =
        p.eligibility(context.employmentStatus(), context.employmentStart(), evaluatedOn);
    return new PlanView(
        p.code(),
        p.name(),
        p.currency(),
        p.monthlyContribution().toPlainString(),
        p.minimumServiceDays(),
        p.effectiveFrom(),
        eligibility.eligible(),
        eligibility.explanation(),
        p.ruleVersion(),
        "DEMONSTRATION");
  }

  public PlanPage plans(String subject, PlanQuery q) {
    return reads.run(
        () -> {
          var c = workforce.self(subject);
          var evaluatedOn = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
          var window =
              com.atlas.modules.benefits.domain.model.EligibilityWindow.forEmployment(
                  c.employmentStatus(), c.employmentStart(), evaluatedOn);
          var result = store.plans(c.tenant(), q, window);
          return new PlanPage(
              result.items().stream().map(p -> view(p, c, evaluatedOn)).toList(),
              result.total(),
              q.page(),
              q.size(),
              evaluatedOn);
        });
  }

  public List<PlanAudit> history(String subject) {
    var c = workforce.self(subject);
    if (!c.role().equals("HR")) throw new AccessDenied();
    return store.history(c.tenant());
  }

  public PlanView create(String subject, CreatePlan command, String correlation) {
    var c = workforce.self(subject);
    if (!c.role().equals("HR")) throw new AccessDenied();
    var errors = new LinkedHashMap<String, String>();
    BigDecimal amount = null;
    try {
      if (command.monthlyContribution() == null
          || !command.monthlyContribution().matches("[0-9]+([.][0-9]{1,2})?"))
        throw new NumberFormatException();
      amount = new BigDecimal(command.monthlyContribution());
    } catch (NumberFormatException e) {
      errors.put("monthlyContribution", "Enter a decimal amount with at most two decimal places.");
    }
    if (command.minimumServiceDays() == null || !command.minimumServiceDays().matches("[0-9]{1,4}"))
      errors.put("minimumServiceDays", "Enter whole service days from 0 to 3650.");
    if (command.reason() == null || command.reason().isBlank() || command.reason().length() > 500)
      errors.put("reason", "A configuration reason of 1–500 characters is required.");
    if (!errors.isEmpty()) throw new InputValidation(errors);
    var plan =
        new BenefitPlan(
            command.code(),
            command.name(),
            command.currency(),
            amount,
            Integer.parseInt(command.minimumServiceDays()),
            command.effectiveFrom());
    if (command.idempotencyKey() == null || !command.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException("A valid idempotency key is required.");
    var normalized =
        new CreatePlan(
            plan.code(),
            plan.name(),
            plan.currency(),
            plan.monthlyContribution().toPlainString(),
            Integer.toString(plan.minimumServiceDays()),
            plan.effectiveFrom(),
            command.reason().trim(),
            command.idempotencyKey());
    return tx.run(
        () -> {
          var previous = store.duplicate(c.tenant(), c.subject(), command.idempotencyKey());
          if (previous.isPresent()) {
            if (!previous.get().equals(normalized))
              throw new CommandConflict("Plan key was used for different input.");
            return view(plan, c, LocalDate.now(clock));
          }
          var today = LocalDate.now(clock);
          if (plan.effectiveFrom().isBefore(today)
              || plan.effectiveFrom().isAfter(today.plusYears(1)))
            throw new InputValidation(
                Map.of("effectiveFrom", "Plan start must be today or within the next year."));
          store.insert(
              c.tenant(),
              c.subject(),
              plan,
              command.reason().trim(),
              command.idempotencyKey(),
              clock.instant(),
              correlation);
          return view(plan, c, LocalDate.now(clock));
        });
  }
}
