package com.atlas.modules.payroll.application.port.in;

import com.atlas.modules.payroll.application.model.PayrollModels.*;
import java.util.List;

public interface PayrollUseCases {
  List<Grant> grants(String subject);

  Page runs(String subject, String entity, int page);

  Run run(String subject, String id);

  Run prepare(String subject, Prepare c, String correlation);

  Run action(String subject, String id, Action c, String correlation);

  List<Audit> history(String subject, String id);

  Document payslip(String subject, String id, String correlation);
}
