package com.atlas.modules.talent.adapter.out.integration;

import com.atlas.modules.corehr.api.TalentWorkforceAccess;
import com.atlas.modules.payroll.api.TalentIntakeAccess;
import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.application.port.out.TalentPorts.Environment;
import com.atlas.modules.talent.domain.model.TalentModels.Compensation;

public final class CoreHrTalentEnvironment implements Environment {
  private final TalentWorkforceAccess w;
  private final TalentIntakeAccess payroll;
  private final com.atlas.modules.payroll.api.CompensationAccess compensation;

  public CoreHrTalentEnvironment(
      TalentWorkforceAccess w,
      TalentIntakeAccess p,
      com.atlas.modules.payroll.api.CompensationAccess c) {
    this.w = w;
    payroll = p;
    compensation = c;
  }

  public java.util.List<String> hrReviewers(String s) {
    return w.hrReviewers(s);
  }

  public boolean hasRole(String s, String e, String r) {
    return w.hasRole(s, e, r);
  }

  public Actor self(String s) {
    var a = w.self(s);
    return new Actor(a.subject(), a.tenant(), a.employeeId(), a.role());
  }

  private Worker map(TalentWorkforceAccess.Worker x) {
    return new Worker(
        x.id(),
        x.name(),
        x.title(),
        x.department(),
        x.managerId(),
        x.status(),
        x.start(),
        x.version());
  }

  public Worker worker(String s, String id) {
    return map(w.worker(s, id));
  }

  public Workers workers(String s, String q, int page) {
    var p = w.workers(s, q, page);
    return new Workers(p.items().stream().map(this::map).toList(), p.total());
  }

  public Hire submitHire(String s, HireInput i, String reason, String key, String corr) {
    var h =
        w.submitHire(
            s,
            new TalentWorkforceAccess.HireInput(
                i.name(),
                i.email(),
                i.phone(),
                i.title(),
                i.department(),
                i.location(),
                i.managerId(),
                i.start()),
            reason,
            key,
            corr);
    return new Hire(h.id(), h.status(), h.employeeId());
  }

  public Hire decideHire(String s, String id, String decision, String reason, String corr) {
    var h = w.decideHire(s, id, decision, reason, corr);
    return new Hire(h.id(), h.status(), h.employeeId());
  }

  public void completeOnboarding(
      String s, String id, long v, String reason, String key, String corr) {
    w.completeOnboarding(s, id, v, reason, key, corr);
  }

  public java.math.BigDecimal currentSalary(
      String s, String employee, java.time.LocalDate date, String currency) {
    var c = compensation.salary(s, employee, date);
    if (!c.currency().equals(currency))
      throw new com.atlas.shared.domain.InputValidation(
          java.util.Map.of(
              "currency", "Proposal currency differs from authoritative payroll compensation."));
    return c.annualSalary();
  }

  public String compensationIntake(String s, Compensation p, String reason, String corr) {
    return payroll.receive(
        s,
        new TalentIntakeAccess.Request(
            p.meta().id(),
            p.currency(),
            p.meta().preparedBy(),
            p.meta().preparedEmployee(),
            p.proposals().stream()
                .map(
                    a ->
                        new TalentIntakeAccess.Award(
                            a.employeeId(),
                            a.currentSalary(),
                            a.proposedSalary(),
                            a.bonus(),
                            a.effectiveDate()))
                .toList()),
        reason,
        corr);
  }
}
