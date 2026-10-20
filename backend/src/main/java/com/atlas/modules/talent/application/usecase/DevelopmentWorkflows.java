package com.atlas.modules.talent.application.usecase;

import static com.atlas.modules.talent.domain.model.TalentPolicy.*;

import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.application.port.out.TalentPorts.*;
import com.atlas.modules.talent.domain.model.TalentModels.*;
import com.atlas.shared.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;

final class DevelopmentWorkflows extends TalentSupport {
  DevelopmentWorkflows(Store s, Environment e, Transactions r, Transactions w, Clock c) {
    super(s, e, r, w, c);
  }

  public Review review(String s, ReviewInput c, String corr) {
    return command(
        s,
        "reviews",
        null,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Review.class,
        a -> {
          manager(a, c.employeeId());
          eligible(a, c.employeeId());
          require(
              c.from() != null && c.through() != null && !c.through().isBefore(c.from()),
              "through",
              "Choose a valid review date range.");
          require(
              c.minimumRating() != null
                  && c.maximumRating() != null
                  && c.minimumRating() >= 0
                  && c.maximumRating() <= 10
                  && c.minimumRating() < c.maximumRating(),
              "rating",
              "Configure an integer rating range within 0–10.");
          return new Review(
              stamp(a, "OPEN"),
              c.employeeId(),
              text(c.cycle(), "cycle", 120),
              c.from(),
              c.through(),
              c.minimumRating(),
              c.maximumRating(),
              List.of(),
              "",
              null,
              "");
        });
  }

  public Review reviewAction(String s, String id, ReviewAction c, String corr) {
    text(c.action(), "action", 40);
    return command(
        s,
        "reviews",
        id,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Review.class,
        a -> {
          var r = load(a, "reviews", id, Review.class);
          revision(r.meta().version(), c.version());
          var goals = new ArrayList<>(r.goals());
          var state = r.meta().status();
          var self = r.selfReview();
          var rating = r.rating();
          var decision = r.decisionComment();
          switch (c.action()) {
            case "GOAL" -> {
              state(state, "OPEN", "RETURNED");
              require(goals.size() < 30, "goals", "Use at most 30 goals.");
              goals.add(new Goal(id(), text(c.goal(), "goal", 1000), ""));
            }
            case "FEEDBACK" -> {
              state(state, "OPEN", "RETURNED");
              int i = -1;
              for (int n = 0; n < goals.size(); n++)
                if (goals.get(n).id().equals(c.goalId())) i = n;
              require(i >= 0, "goalId", "Select an existing goal.");
              var g = goals.get(i);
              goals.set(i, new Goal(g.id(), g.text(), text(c.feedback(), "feedback", 2000)));
            }
            case "SUBMIT" -> {
              if (!a.employeeId().equals(r.employeeId())) throw new AccessDenied();
              state(state, "OPEN", "RETURNED");
              require(!goals.isEmpty(), "goals", "Set at least one goal before submission.");
              self = text(c.selfReview(), "selfReview", 3000);
              state = "SUBMITTED";
            }
            case "APPROVE", "RETURN" -> {
              manager(a, r.employeeId());
              if (a.employeeId().equals(r.employeeId())) throw new AccessDenied();
              state(state, "SUBMITTED");
              decision = c.reason();
              if (c.action().equals("APPROVE")) {
                require(
                    c.rating() != null
                        && c.rating() >= r.minimumRating()
                        && c.rating() <= r.maximumRating(),
                    "rating",
                    "Use the cycle's configured integer rating range.");
                rating = c.rating();
                state = "COMPLETED";
              } else state = "RETURNED";
            }
            default ->
                throw new InputValidation(Map.of("action", "Choose a supported review action."));
          }
          return new Review(
              r.meta().next(state, now()),
              r.employeeId(),
              r.cycle(),
              r.from(),
              r.through(),
              r.minimumRating(),
              r.maximumRating(),
              List.copyOf(goals),
              self,
              rating,
              decision);
        });
  }

  public Course course(String s, CourseInput c, String corr) {
    hr(env.self(s));
    return command(
        s,
        "courses",
        null,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Course.class,
        a -> {
          hr(a);
          require(
              c.validDays() != null && c.validDays() >= 0 && c.validDays() <= 3650,
              "validDays",
              "Use 0 for no expiration or 1–3650 days.");
          require(
              Set.of("ALL", "HR", "MANAGER", "EMPLOYEE").contains(c.role() == null ? "" : c.role()),
              "role",
              "Choose an assignment role.");
          return new Course(
              stamp(a, "PUBLISHED"),
              text(c.title(), "title", 180),
              text(c.description(), "description", 3000),
              c.role(),
              c.validDays());
        });
  }

  public Enrollment assign(String s, Assignment c, String corr) {
    return command(
        s,
        "learning",
        null,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Enrollment.class,
        a -> {
          if (!a.employeeId().equals(c.employeeId())) manager(a, c.employeeId());
          eligible(a, c.employeeId());
          if (db.enrolled(a.tenant(), c.courseId(), c.employeeId()))
            throw new CommandConflict(
                "This course is already assigned to that worker. Open the existing assignment.");
          var course = load(a, "courses", c.courseId(), Course.class);
          require(
              env.hasRole(s, c.employeeId(), course.role()),
              "employeeId",
              "This worker does not match the course assignment role.");
          require(
              c.due() != null && !c.due().isBefore(today()),
              "due",
              "Choose today or a future due date.");
          return new Enrollment(
              stamp(a, "ASSIGNED"),
              course.meta().id(),
              c.employeeId(),
              course.title(),
              course.validDays(),
              c.due(),
              null,
              null,
              "");
        });
  }

  public Enrollment learning(String s, String id, LearningAction c, String corr) {
    text(c.action(), "action", 40);
    return command(
        s,
        "learning",
        id,
        c,
        c.reason(),
        c.idempotencyKey(),
        corr,
        Enrollment.class,
        a -> {
          var e = load(a, "learning", id, Enrollment.class);
          revision(e.meta().version(), c.version());
          state(e.meta().status(), "ASSIGNED");
          require("COMPLETE".equals(c.action()), "action", "Choose COMPLETE.");
          if (!a.employeeId().equals(e.employeeId())) throw new AccessDenied();
          return new Enrollment(
              e.meta().next("COMPLETED", now()),
              e.courseId(),
              e.employeeId(),
              e.courseTitle(),
              e.validDays(),
              e.due(),
              today(),
              e.validDays() == 0 ? null : today().plusDays(e.validDays()),
              text(c.evidence(), "evidence", 2000));
        });
  }
}
