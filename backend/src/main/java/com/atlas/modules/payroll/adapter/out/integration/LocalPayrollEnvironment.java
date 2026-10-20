package com.atlas.modules.payroll.adapter.out.integration;

import com.atlas.modules.corehr.api.*;
import com.atlas.modules.finance.api.*;
import com.atlas.modules.payroll.application.model.PayrollModels.*;
import com.atlas.modules.payroll.application.port.out.PayrollPorts.Environment;
import com.atlas.modules.timekeeping.api.ApprovedTimeAccess;
import java.util.List;

public final class LocalPayrollEnvironment implements Environment {
  private final WorkforceAccess workforce;
  private final FinanceReferenceAccess refs;
  private final PayrollFinanceAccess finance;
  private final ApprovedTimeAccess time;
  private final PayrollJournalAccess journals;

  public LocalPayrollEnvironment(
      WorkforceAccess w,
      FinanceReferenceAccess r,
      PayrollFinanceAccess f,
      ApprovedTimeAccess t,
      PayrollJournalAccess j) {
    workforce = w;
    refs = r;
    finance = f;
    time = t;
    journals = j;
  }

  public Identity self(String subject) {
    workforce.requireActive(subject);
    var a = workforce.self(subject);
    return new Identity(a.subject(), a.tenant(), a.employeeId());
  }

  public List<Grant> grants(String subject) {
    return finance.grants(subject).stream()
        .map(g -> new Grant(g.entity(), g.permission()))
        .toList();
  }

  public Ledger ledger(String subject, String entity) {
    var l = finance.ledger(subject, entity);
    return new Ledger(l.entity(), l.currency(), l.effectiveFrom(), l.costCentres());
  }

  public Worker worker(String subject, String id) {
    var w = refs.worker(subject, id);
    return new Worker(w.id(), w.status(), w.start(), w.entityCode());
  }

  public Source source(String subject, String id) {
    var s = time.read(subject, id);
    return new Source(
        s.id(),
        s.employeeId(),
        s.episodeStart(),
        s.week(),
        s.version(),
        s.days().stream().map(d -> new Day(d.date(), d.minutes(), d.activity())).toList(),
        s.totalMinutes());
  }

  public String journal(String subject, Run r, String reason, String key, String correlation) {
    return journals.prepare(
        subject,
        new PayrollJournalAccess.Request(
            r.id(),
            r.entity(),
            r.currency(),
            r.postingDate(),
            r.costCentre(),
            r.gross(),
            r.deduction(),
            r.net(),
            reason,
            key),
        correlation);
  }
}
