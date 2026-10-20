package com.atlas.modules.timekeeping.application.port.in;

import com.atlas.modules.timekeeping.application.model.TimeModels.*;

public interface TimeUseCases {
  PeriodContext period(String subject, java.time.LocalDate week);

  Period configurePeriod(String subject, SavePeriod command, String correlation);

  Page<Shift> attendance(String subject, String employeeId, int page, int size);

  Shift punch(String subject, String action, Punch command, String correlation);

  Shift correct(String subject, Correction command, String correlation);

  Page<Sheet> sheets(String subject, boolean review, String status, int page, int size);

  Sheet approvedSource(String subject, String id);

  SheetContext sheet(String subject, String id);

  Dashboard dashboard(String subject, java.time.LocalDate week);

  Report report(String subject, ReportQuery query);

  Report exportReport(String subject, ReportQuery query, String correlation);

  Sheet copy(String subject, CopySheet command, String correlation);

  Sheet save(String subject, SaveSheet command, String correlation);

  Sheet decide(String subject, String id, Decision command, String correlation);

  java.util.List<Sheet> decideSelected(String subject, BulkDecision command, String correlation);

  Page<Audit> history(String subject, String employeeId, int page, int size);
}
