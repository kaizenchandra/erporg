package com.atlas.modules.payroll.application.port.in;

import com.atlas.modules.payroll.api.*;
import com.atlas.modules.payroll.application.model.CompensationModels.*;
import com.atlas.modules.payroll.application.model.PayrollModels.Source;
import java.time.LocalDate;

public interface CompensationUseCases extends CompensationAccess {
  State state(String subject, String employee);

  Setup propose(String subject, Baseline input, String correlation);

  Setup decide(String subject, String id, Decision input, String correlation);

  void apply(String subject, TalentIntakeAccess.Intake intake, String reason, String correlation);

  Calculation capture(
      String subject,
      String runId,
      String entity,
      String currency,
      Source source,
      LocalDate postingDate,
      String reason,
      String correlation);

  Calculation calculation(String subject, String runId);
}
