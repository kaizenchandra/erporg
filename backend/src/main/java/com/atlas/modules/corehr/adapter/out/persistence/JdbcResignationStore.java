package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.port.out.ResignationStore;
import com.atlas.modules.corehr.domain.model.*;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcResignationStore implements ResignationStore {
  private final JdbcClient db;
  private static final String SELECT =
      "select id,employee_id,last_working_date,reason,status,requested_by,employee_version,recorded_at,version,decided_by,reviewer_employee_id,comment,decided_at from hr_resignation where tenant=:tenant";

  public JdbcResignationStore(JdbcClient db) {
    this.db = db;
  }

  public ResignationPage list(String tenant, String employee, RequestQuery q) {
    String where =
        " and (cast(:employee as varchar) is null or employee_id=:employee) and (:status='ALL' or status=:status)";
    var params = new HashMap<String, Object>();
    params.put("tenant", tenant);
    params.put("employee", employee);
    params.put("status", q.status());
    var total =
        db.sql("select count(*) from (" + SELECT + where + ") scoped")
            .params(params)
            .query(Long.class)
            .single();
    var items =
        db.sql(SELECT + where + " order by recorded_at desc,id limit :size offset :offset")
            .params(params)
            .param("size", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(Resignation.class)
            .list();
    return new ResignationPage(items, total, q.page(), q.size());
  }

  public Optional<Resignation> duplicate(String tenant, String subject, String key) {
    return db.sql(SELECT + " and requested_by=:subject and idempotency_key=:key")
        .param("tenant", tenant)
        .param("subject", subject)
        .param("key", key)
        .query(Resignation.class)
        .optional();
  }

  public boolean outstanding(String tenant, String employee) {
    return db.sql(
            "select exists(select 1 from hr_resignation where tenant=:tenant and employee_id=:employee and status in('SUBMITTED','APPROVED'))")
        .param("tenant", tenant)
        .param("employee", employee)
        .query(Boolean.class)
        .single();
  }

  public Resignation get(String tenant, String id, boolean lock) {
    return db.sql(SELECT + " and id=:id" + (lock ? " for update" : ""))
        .param("tenant", tenant)
        .param("id", id)
        .query(Resignation.class)
        .optional()
        .orElseThrow(ChangeRequest.Denied::new);
  }

  public Replay decision(String tenant, String id) {
    return db.sql(
            "select decision_key,decision_input_version from hr_resignation where tenant=:tenant and id=:id")
        .param("tenant", tenant)
        .param("id", id)
        .query((rs, n) -> new Replay(rs.getString(1), rs.getObject(2, Long.class)))
        .single();
  }

  public java.util.List<EmploymentEpisodeRef> episodes(String tenant, String employee) {
    return db.sql(
            "select 1 number,start_date from hr_employee where tenant=:tenant and id=:employee union all select number,start_date from hr_employment_episode where tenant=:tenant and employee_id=:employee order by number")
        .param("tenant", tenant)
        .param("employee", employee)
        .query(EmploymentEpisodeRef.class)
        .list();
  }

  public CaseEpisode episode(String tenant, String id) {
    return db.sql(
            "select request_id,employee_id,episode_number,episode_start_date,source_recorded_at,linked_at,origin,linked_by,correlation_id from hr_case_episode where tenant=:tenant and request_id=:id")
        .param("tenant", tenant)
        .param("id", id)
        .query(CaseEpisode.class)
        .optional()
        .orElseThrow(
            () ->
                new ChangeRequest.Conflict("Case episode provenance is unavailable. Contact HR."));
  }

  public void insert(
      String tenant, Resignation r, EmploymentEpisodeRef episode, String key, String correlation) {
    db.sql(
            "insert into hr_resignation(tenant,id,employee_id,last_working_date,reason,status,requested_by,employee_version,recorded_at,version,idempotency_key) values(:tenant,:id,:employee,:date,:reason,'SUBMITTED',:actor,:version,:now,0,:key)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("employee", r.employeeId())
        .param("date", r.lastWorkingDate())
        .param("reason", r.reason())
        .param("actor", r.requestedBy())
        .param("version", r.employeeVersion())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .param("key", key)
        .update();
    audit(tenant, r, null, r.requestedBy(), r.reason(), r.recordedAt(), correlation);
    db.sql(
            "insert into hr_case_episode(tenant,request_id,employee_id,episode_number,episode_start_date,source_recorded_at,linked_at,origin,linked_by,correlation_id) values(:tenant,:id,:employee,:number,:start,:now,:now,'COMMAND',:actor,:correlation)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("employee", r.employeeId())
        .param("number", episode.number())
        .param("start", episode.startDate())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .param("actor", r.requestedBy())
        .param("correlation", correlation)
        .update();
  }

  public void decide(
      String tenant, Resignation r, String key, long inputVersion, String correlation) {
    int changed =
        db.sql(
                "update hr_resignation set status=:status,version=:version,decided_by=:actor,reviewer_employee_id=:reviewer,comment=:comment,decided_at=:now,decision_key=:key,decision_input_version=:input where tenant=:tenant and id=:id and version=:input and status='SUBMITTED'")
            .param("status", r.status())
            .param("version", r.version())
            .param("actor", r.decidedBy())
            .param("reviewer", r.reviewerEmployeeId())
            .param("comment", r.comment())
            .param("now", r.decidedAt().atOffset(ZoneOffset.UTC))
            .param("key", key)
            .param("input", inputVersion)
            .param("tenant", tenant)
            .param("id", r.id())
            .update();
    if (changed != 1) throw new ChangeRequest.Conflict("The request changed. Refresh and retry.");
    audit(tenant, r, "SUBMITTED", r.decidedBy(), r.comment(), r.decidedAt(), correlation);
    if (r.status().equals("APPROVED"))
      db.sql(
              "insert into hr_resignation_handoff(tenant,request_id,employee_id,effective_date,recorded_at,correlation_id,status) values(:tenant,:id,:employee,:date,:now,:correlation,'PENDING_EXIT_COORDINATION')")
          .param("tenant", tenant)
          .param("id", r.id())
          .param("employee", r.employeeId())
          .param("date", r.lastWorkingDate())
          .param("now", r.decidedAt().atOffset(ZoneOffset.UTC))
          .param("correlation", correlation)
          .update();
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) values(:tenant,:subject,:message,:now)")
        .param("tenant", tenant)
        .param("subject", r.requestedBy())
        .param(
            "message",
            "Resignation request "
                + r.status().toLowerCase()
                + ". Review the decision in Resignations. Exit coordination remains separate.")
        .param("now", r.decidedAt().atOffset(ZoneOffset.UTC))
        .update();
  }

  private void audit(
      String tenant,
      Resignation r,
      String old,
      String actor,
      String reason,
      java.time.Instant now,
      String correlation) {
    db.sql(
            "insert into hr_resignation_audit(tenant,request_id,old_status,new_status,actor,reason,effective_date,recorded_at,correlation_id) values(:tenant,:id,:old,:status,:actor,:reason,:date,:now,:correlation)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("old", old)
        .param("status", r.status())
        .param("actor", actor)
        .param("reason", reason)
        .param("date", r.lastWorkingDate())
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("correlation", correlation)
        .update();
  }

  private static final String WITHDRAWAL =
      "select request_id,employee_id,actor,reason,input_version,new_version,recorded_at,correlation_id from hr_resignation_withdrawal where tenant=:tenant";

  public Optional<ResignationWithdrawal> withdrawal(String tenant, String id) {
    return db.sql(WITHDRAWAL + " and request_id=:id")
        .param("tenant", tenant)
        .param("id", id)
        .query(ResignationWithdrawal.class)
        .optional();
  }

  public Optional<ResignationWithdrawal> withdrawalDuplicate(
      String tenant, String actor, String key) {
    return db.sql(WITHDRAWAL + " and actor=:actor and idempotency_key=:key")
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query(ResignationWithdrawal.class)
        .optional();
  }

  public void withdraw(String tenant, ResignationWithdrawal r, String key) {
    int changed =
        db.sql(
                "update hr_resignation set status='WITHDRAWN',version=:next where tenant=:tenant and id=:id and status='SUBMITTED' and version=:input")
            .param("tenant", tenant)
            .param("id", r.requestId())
            .param("input", r.inputVersion())
            .param("next", r.newVersion())
            .update();
    if (changed != 1) throw new ChangeRequest.Conflict("Resignation changed. Refresh the list.");
    db.sql(
            "insert into hr_resignation_withdrawal(tenant,request_id,employee_id,actor,reason,input_version,new_version,recorded_at,correlation_id,idempotency_key) values(:tenant,:id,:employee,:actor,:reason,:input,:next,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", r.requestId())
        .param("employee", r.employeeId())
        .param("actor", r.actor())
        .param("reason", r.reason())
        .param("input", r.inputVersion())
        .param("next", r.newVersion())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", r.correlationId())
        .param("key", key)
        .update();
    db.sql(
            "insert into hr_resignation_audit(tenant,request_id,old_status,new_status,actor,reason,effective_date,recorded_at,correlation_id) select :tenant,:id,'SUBMITTED','WITHDRAWN',:actor,:reason,last_working_date,:now,:correlation from hr_resignation where tenant=:tenant and id=:id")
        .param("tenant", tenant)
        .param("id", r.requestId())
        .param("actor", r.actor())
        .param("reason", r.reason())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", r.correlationId())
        .update();
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) select :tenant,m.subject,'A pending resignation was withdrawn. Review its current private status in Resignations.',:now from identity_membership m where m.tenant=:tenant and m.role='HR' and m.employee_id<>:employee")
        .param("tenant", tenant)
        .param("employee", r.employeeId())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .update();
  }

  public List<ResignationAudit> history(String tenant, String id) {
    return db.sql(
            "select old_status,new_status,actor,reason,effective_date,recorded_at,correlation_id from hr_resignation_audit where tenant=:tenant and request_id=:id order by recorded_at,id")
        .param("tenant", tenant)
        .param("id", id)
        .query(ResignationAudit.class)
        .list();
  }
}
