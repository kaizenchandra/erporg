package com.atlas.modules.talent.application.port.in;

import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.domain.model.TalentModels.*;
import java.util.List;

public interface TalentUseCases {
  List<ResponsibilityHistory> delegationHistory(String subject, String kind, String id, int page);

  List<ApprovalTask> approvalInbox(String subject, int page);

  List<String> approvalDelegates(String subject);

  Delegation delegate(String subject, Delegate command, String correlation);

  DelegationRevocation revokeDelegation(
      String subject, String id, RevokeDelegation command, String correlation);

  List<Notice> notices(String subject, int page);

  Export export(String subject, String kind, String employee, int page, String correlation);

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
