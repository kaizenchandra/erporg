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

  public void insert(String tenant, Resignation r, String key, String correlation) {
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

  public List<ResignationAudit> history(String tenant, String id) {
    return db.sql(
            "select old_status,new_status,actor,reason,effective_date,recorded_at,correlation_id from hr_resignation_audit where tenant=:tenant and request_id=:id order by recorded_at,id")
        .param("tenant", tenant)
        .param("id", id)
        .query(ResignationAudit.class)
        .list();
  }
}
