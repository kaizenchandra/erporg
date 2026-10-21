package com.atlas.modules.payroll.application.port.in;

import com.atlas.modules.payroll.api.TalentIntakeAccess.*;
import java.util.List;

public interface TalentIntakeUseCases {
  String receive(String subject, Request request, String reason, String correlation);

  Intake synchronize(String subject, String source, String reason, String correlation);

  List<Intake> pending(String subject, int page);
}
