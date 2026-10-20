package com.atlas.modules.talent.application.usecase;

import static com.atlas.modules.talent.domain.model.TalentPolicy.*;

import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.application.port.in.TalentUseCases;
import com.atlas.modules.talent.application.port.out.TalentPorts.*;
import com.atlas.modules.talent.domain.model.TalentModels.*;
import com.atlas.shared.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;

/** Published talent workflow contracts; each capability owns its orchestration. */
public final class TalentService extends TalentSupport implements TalentUseCases {
  private final RecruitingWorkflows recruiting;
  private final DevelopmentWorkflows development;
  private final PlanningWorkflows planning;

  public TalentService(Store s, Environment e, Transactions r, Transactions w, Clock c) {
    super(s, e, r, w, c);
    recruiting = new RecruitingWorkflows(s, e, r, w, c);
    development = new DevelopmentWorkflows(s, e, r, w, c);
    planning = new PlanningWorkflows(s, e, r, w, c);
  }

  public Requisition requisition(String s, RequisitionInput c, String corr) {
    return recruiting.requisition(s, c, corr);
  }

  public Requisition requisitionAction(String s, String id, Decision c, String corr) {
    return recruiting.requisitionAction(s, id, c, corr);
  }

  public Requisition candidate(String s, String id, CandidateInput c, String corr) {
    return recruiting.candidate(s, id, c, corr);
  }

  public Onboarding joining(String s, String id, Joining c, String corr) {
    return recruiting.joining(s, id, c, corr);
  }

  public Review review(String s, ReviewInput c, String corr) {
    return development.review(s, c, corr);
  }

  public Review reviewAction(String s, String id, ReviewAction c, String corr) {
    return development.reviewAction(s, id, c, corr);
  }

  public Course course(String s, CourseInput c, String corr) {
    return development.course(s, c, corr);
  }

  public Enrollment assign(String s, Assignment c, String corr) {
    return development.assign(s, c, corr);
  }

  public Enrollment learning(String s, String id, LearningAction c, String corr) {
    return development.learning(s, id, c, corr);
  }

  public Compensation compensation(String s, CompensationInput c, String corr) {
    return planning.compensation(s, c, corr);
  }

  public Compensation compensationAction(String s, String id, Decision c, String corr) {
    return planning.compensationAction(s, id, c, corr);
  }

  public Succession succession(String s, SuccessionInput c, String corr) {
    return planning.succession(s, c, corr);
  }

  public Succession successionUpdate(String s, String id, SuccessionUpdate c, String corr) {
    return planning.successionUpdate(s, id, c, corr);
  }

  public Scenario scenario(String s, ScenarioInput c, String corr) {
    return planning.scenario(s, c, corr);
  }
}
