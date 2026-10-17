package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.port.out.EndRescheduleStore;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcEndRescheduleStore implements EndRescheduleStore {
  private final JdbcClient db;

  public JdbcEndRescheduleStore(JdbcClient db) {
    this.db = db;
  }

  private static final String SELECT =
      "select id,request_id,employee_id,last_working_date,prior_inactive_date,old_status,actor,actor_employee_id,reason,input_version,recorded_at,correlation_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id from hr_end_reschedule_current r where tenant=:tenant";

  public List<EndReschedule> history(String tenant, String id) {
    return db.sql(SELECT + " and request_id=:id order by recorded_at,id")
        .param("tenant", tenant)
        .param("id", id)
        .query(EndReschedule.class)
        .list();
  }

  public Optional<EndReschedule> duplicate(
      String tenant, String actor, String key, boolean review) {
    String clause =
        review
            ? " and exists(select 1 from hr_end_reschedule_decision d where d.tenant=r.tenant and d.reschedule_id=r.id and d.reviewer=:actor and d.idempotency_key=:key)"
            : " and exists(select 1 from hr_end_reschedule s where s.tenant=r.tenant and s.id=r.id and s.actor=:actor and s.idempotency_key=:key)";
    return db.sql(SELECT + clause)
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query(EndReschedule.class)
        .optional();
  }

  public void submit(String tenant, EndReschedule r, String key) {
    db.sql(
            "insert into hr_end_reschedule(tenant,id,request_id,employee_id,last_working_date,prior_inactive_date,old_status,actor,actor_employee_id,reason,input_version,recorded_at,correlation_id,idempotency_key) values(:tenant,:id,:request,:employee,:last,:prior,:status,:actor,:actorEmployee,:reason,:version,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("request", r.requestId())
        .param("employee", r.employeeId())
        .param("last", r.lastWorkingDate())
        .param("prior", r.priorInactiveDate(), java.sql.Types.DATE)
        .param("status", r.oldStatus())
        .param("actor", r.actor())
        .param("actorEmployee", r.actorEmployeeId())
        .param("reason", r.reason())
        .param("version", r.inputVersion())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", r.correlationId())
        .param("key", key)
        .update();
    notify(
        tenant,
        r.employeeId(),
        "HR requested a replacement employment-end date. Existing access rules remain until independent approval.",
        r.recordedAt());
  }

  public void review(String tenant, EndReschedule r, String key) {
    if (r.status().equals("APPROVED")) {
      int n =
          db.sql(
                  "update hr_employee set version=version+1 where tenant=:tenant and id=:employee and version=:version")
              .param("tenant", tenant)
              .param("employee", r.employeeId())
              .param("version", r.reviewVersion())
              .update();
      if (n != 1) throw new ChangeRequest.Conflict("Employee changed. Refresh context.");
    }
    db.sql(
            "insert into hr_end_reschedule_decision(tenant,reschedule_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id,idempotency_key) values(:tenant,:id,:status,:reviewer,:reviewerEmployee,:comment,:version,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("status", r.status())
        .param("reviewer", r.reviewer())
        .param("reviewerEmployee", r.reviewerEmployeeId())
        .param("comment", r.comment())
        .param("version", r.reviewVersion())
        .param("now", r.reviewedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", r.reviewCorrelationId())
        .param("key", key)
        .update();
    if (r.status().equals("APPROVED"))
      for (var target : List.of("FINAL_PAY", "EXTERNAL_IDENTITY"))
        db.sql(
                "insert into hr_end_reschedule_handoff(tenant,reschedule_id,target,inactive_date,recorded_at,correlation_id,status) values(:tenant,:id,:target,:date,:now,:correlation,'PENDING')")
            .param("tenant", tenant)
            .param("id", r.id())
            .param("target", target)
            .param("date", r.lastWorkingDate().plusDays(1))
            .param("now", r.reviewedAt().atOffset(ZoneOffset.UTC))
            .param("correlation", r.reviewCorrelationId())
            .update();
    notify(
        tenant,
        r.employeeId(),
        r.status().equals("APPROVED")
            ? "HR independently approved a replacement employment-end date. Contact HR for details. Final-pay and external identity steps remain pending."
            : "HR rejected the replacement employment-end date. Existing access rules remain.",
        r.reviewedAt());
  }

  private void notify(String tenant, String employee, String message, Instant now) {
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) select :tenant,subject,:message,:now from identity_membership where tenant=:tenant and employee_id=:employee")
        .param("tenant", tenant)
        .param("employee", employee)
        .param("message", message)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .update();
  }
}
