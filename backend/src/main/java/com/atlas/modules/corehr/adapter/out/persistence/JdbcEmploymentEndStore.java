package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.port.out.EmploymentEndStore;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcEmploymentEndStore implements EmploymentEndStore {
  private final JdbcClient db;

  public JdbcEmploymentEndStore(JdbcClient db) {
    this.db = db;
  }

  private static final String SELECT =
      "select request_id,employee_id,last_working_date,inactive_date,actor,actor_employee_id,reason,input_version,new_version,old_status,new_status,recorded_at,correlation_id from hr_employment_end where tenant=:tenant";

  public Optional<EmploymentEnd> current(String tenant, String id) {
    return db.sql(
            SELECT.replace("from hr_employment_end where", "from hr_employment_end_current where")
                + " and request_id=:id")
        .param("tenant", tenant)
        .param("id", id)
        .query(EmploymentEnd.class)
        .optional();
  }

  public boolean replacementReviewOpen(String tenant, String id) {
    return db.sql(
            "select exists(select 1 from hr_end_reschedule_current where tenant=:tenant and request_id=:id and status in('SUBMITTED','APPROVED'))")
        .param("tenant", tenant)
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  public Optional<EmploymentEnd> get(String tenant, String id) {
    return db.sql(SELECT + " and request_id=:id")
        .param("tenant", tenant)
        .param("id", id)
        .query(EmploymentEnd.class)
        .optional();
  }

  public Optional<EmploymentEnd> duplicate(String tenant, String actor, String key) {
    return db.sql(SELECT + " and actor=:actor and idempotency_key=:key")
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query(EmploymentEnd.class)
        .optional();
  }

  public long openTasks(String tenant, String id, String employee) {
    return db.sql(
            "select count(*) from hr_exit_task_current where tenant=:tenant and status='OPEN' and (request_id=:id or assignee_id=:employee)")
        .param("tenant", tenant)
        .param("id", id)
        .param("employee", employee)
        .query(Long.class)
        .single();
  }

  public long activeReports(String tenant, String manager, LocalDate date) {
    return db.sql(
            "select count(*) from hr_employee e where e.tenant=:tenant and e.start_date<=:date and hr_employment_status(e.tenant,e.id,e.status,:date)<>'Ended' and hr_employment_value(e.tenant,e.id,'managerId',e.manager_id,:date)=:manager")
        .param("tenant", tenant)
        .param("manager", manager)
        .param("date", date)
        .query(Long.class)
        .single();
  }

  public void insert(String tenant, EmploymentEnd e, String key) {
    int changed =
        db.sql(
                "update hr_employee set version=:new where tenant=:tenant and id=:id and version=:old")
            .param("new", e.newVersion())
            .param("tenant", tenant)
            .param("id", e.employeeId())
            .param("old", e.inputVersion())
            .update();
    if (changed != 1) throw new ChangeRequest.Conflict("Employee changed. Refresh the case.");
    db.sql(
            "insert into hr_employment_end(tenant,request_id,employee_id,last_working_date,inactive_date,actor,actor_employee_id,reason,input_version,new_version,old_status,new_status,recorded_at,correlation_id,idempotency_key) values(:tenant,:request,:employee,:last,:inactive,:actor,:actorEmployee,:reason,:input,:version,:old,'Ended',:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("request", e.requestId())
        .param("employee", e.employeeId())
        .param("last", e.lastWorkingDate())
        .param("inactive", e.inactiveDate())
        .param("actor", e.actor())
        .param("actorEmployee", e.actorEmployeeId())
        .param("reason", e.reason())
        .param("input", e.inputVersion())
        .param("version", e.newVersion())
        .param("old", e.oldStatus())
        .param("now", e.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", e.correlationId())
        .param("key", key)
        .update();
    for (var target : List.of("FINAL_PAY", "EXTERNAL_IDENTITY"))
      db.sql(
              "insert into hr_employment_end_handoff(tenant,employee_id,target,inactive_date,recorded_at,correlation_id,status) values(:tenant,:employee,:target,:date,:now,:correlation,'PENDING')")
          .param("tenant", tenant)
          .param("employee", e.employeeId())
          .param("target", target)
          .param("date", e.inactiveDate())
          .param("now", e.recordedAt().atOffset(ZoneOffset.UTC))
          .param("correlation", e.correlationId())
          .update();
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) select :tenant,subject,'An employment end was scheduled by HR. Contact HR for details; final-pay and external account steps remain separate.',:now from identity_membership where tenant=:tenant and employee_id=:employee")
        .param("tenant", tenant)
        .param("employee", e.employeeId())
        .param("now", e.recordedAt().atOffset(ZoneOffset.UTC))
        .update();
  }

  private static final String WITHDRAWAL_SELECT =
      "select id,request_id,employee_id,actor,actor_employee_id,reason,input_version,recorded_at,correlation_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id from hr_end_withdrawal_current w where tenant=:tenant";

  public List<EndWithdrawal> withdrawals(String tenant, String id) {
    return db.sql(WITHDRAWAL_SELECT + " and request_id=:id order by recorded_at,id")
        .param("tenant", tenant)
        .param("id", id)
        .query(EndWithdrawal.class)
        .list();
  }

  public Optional<EndWithdrawal> withdrawalDuplicate(
      String tenant, String actor, String key, boolean review) {
    String clause =
        review
            ? " and exists(select 1 from hr_end_withdrawal_decision d where d.tenant=w.tenant and d.withdrawal_id=w.id and d.reviewer=:actor and d.idempotency_key=:key)"
            : " and exists(select 1 from hr_end_withdrawal r where r.tenant=w.tenant and r.id=w.id and r.actor=:actor and r.idempotency_key=:key)";
    return db.sql(WITHDRAWAL_SELECT + clause)
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query(EndWithdrawal.class)
        .optional();
  }

  public void submitWithdrawal(String tenant, EndWithdrawal w, String key) {
    db.sql(
            "insert into hr_end_withdrawal(tenant,id,request_id,employee_id,actor,actor_employee_id,reason,input_version,recorded_at,correlation_id,idempotency_key) values(:tenant,:id,:request,:employee,:actor,:actorEmployee,:reason,:version,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", w.id())
        .param("request", w.requestId())
        .param("employee", w.employeeId())
        .param("actor", w.actor())
        .param("actorEmployee", w.actorEmployeeId())
        .param("reason", w.reason())
        .param("version", w.inputVersion())
        .param("now", w.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", w.correlationId())
        .param("key", key)
        .update();
    notifyWithdrawal(
        tenant,
        w.employeeId(),
        "HR requested withdrawal of a scheduled employment end. The cutoff remains in force until independent approval.",
        w.recordedAt());
  }

  public void reviewWithdrawal(String tenant, EndWithdrawal w, String key) {
    if (w.status().equals("APPROVED")) {
      int changed =
          db.sql(
                  "update hr_employee set version=version+1 where tenant=:tenant and id=:employee and version=:version")
              .param("tenant", tenant)
              .param("employee", w.employeeId())
              .param("version", w.reviewVersion())
              .update();
      if (changed != 1) throw new ChangeRequest.Conflict("Employee changed. Refresh context.");
    }
    db.sql(
            "insert into hr_end_withdrawal_decision(tenant,withdrawal_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id,idempotency_key) values(:tenant,:id,:status,:reviewer,:reviewerEmployee,:comment,:version,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", w.id())
        .param("status", w.status())
        .param("reviewer", w.reviewer())
        .param("reviewerEmployee", w.reviewerEmployeeId())
        .param("comment", w.comment())
        .param("version", w.reviewVersion())
        .param("now", w.reviewedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", w.reviewCorrelationId())
        .param("key", key)
        .update();
    notifyWithdrawal(
        tenant,
        w.employeeId(),
        w.status().equals("APPROVED")
            ? "HR approved withdrawal of the scheduled employment end. Local inactivity and pending handoffs from that schedule are suppressed; external actions remain separate."
            : "HR rejected withdrawal of the scheduled employment end. The original cutoff remains in force.",
        w.reviewedAt());
  }

  private void notifyWithdrawal(String tenant, String employee, String message, Instant now) {
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) select :tenant,subject,:message,:now from identity_membership where tenant=:tenant and employee_id=:employee")
        .param("tenant", tenant)
        .param("employee", employee)
        .param("message", message)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .update();
  }
}
