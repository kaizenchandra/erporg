package com.atlas.modules.talent.application.port.in;

import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.domain.model.TalentModels.*;
import java.util.List;

public interface TalentUseCases {
  Context context(String subject, String search, int page);

  Object page(String subject, String kind, String employee, int page);

  Object record(String subject, String kind, String id);

  List<Audit> history(String subject, String kind, String id, int page);

  Requisition requisition(String subject, RequisitionInput c, String correlation);

  Requisition requisitionAction(String subject, String id, Decision c, String correlation);

  Requisition candidate(String subject, String id, CandidateInput c, String correlation);

  Onboarding joining(String subject, String id, Joining c, String correlation);

  Review review(String subject, ReviewInput c, String correlation);

  Review reviewAction(String subject, String id, ReviewAction c, String correlation);

  Course course(String subject, CourseInput c, String correlation);

  Enrollment assign(String subject, Assignment c, String correlation);

  Enrollment learning(String subject, String id, LearningAction c, String correlation);

  Compensation compensation(String subject, CompensationInput c, String correlation);

  Compensation compensationAction(String subject, String id, Decision c, String correlation);

  Succession succession(String subject, SuccessionInput c, String correlation);

  Succession successionUpdate(String subject, String id, SuccessionUpdate c, String correlation);

  Scenario scenario(String subject, ScenarioInput c, String correlation);
}
