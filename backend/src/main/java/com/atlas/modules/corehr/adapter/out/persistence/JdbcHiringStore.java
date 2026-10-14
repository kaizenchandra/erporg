package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.port.out.HiringStore;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcHiringStore implements HiringStore {
  private final JdbcClient db;
  private static final String SELECT =
      "select id,name,email,phone,title,department,location,manager_id,start_date,reason,status,requested_by,requester_employee_id,employee_id,recorded_at,decided_by,comment from hr_hire_request where tenant=:tenant";

  public JdbcHiringStore(JdbcClient db) {
    this.db = db;
  }

  public HirePage hires(String tenant, RequestQuery q) {
    var params = Map.<String, Object>of("tenant", tenant, "status", q.status());
    String scoped = SELECT + " and (:status='ALL' or status=:status)";
    long total =
        db.sql("select count(*) from (" + scoped + ") scoped")
            .params(params)
            .query(Long.class)
            .single();
    var items =
        db.sql(scoped + " order by recorded_at desc,id asc limit :limit offset :offset")
            .params(params)
            .param("limit", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(HireRequest.class)
            .list();
    return new HirePage(items, total, q.page(), q.size());
  }

  public Optional<HireRequest> duplicate(String tenant, String subject, String key) {
    return db.sql(SELECT + " and requested_by=:subject and idempotency_key=:key")
        .param("tenant", tenant)
        .param("subject", subject)
        .param("key", key)
        .query(HireRequest.class)
        .optional();
  }

  public HireRequest hire(String tenant, String id, boolean lock) {
    return db.sql(SELECT + " and id=:id" + (lock ? " for update" : ""))
        .param("tenant", tenant)
        .param("id", id)
        .query(HireRequest.class)
        .optional()
        .orElseThrow(ChangeRequest.Denied::new);
  }

  public boolean emailExists(String tenant, String email) {
    return db.sql(
            "select exists(select 1 from hr_employee where tenant=:tenant and lower(email)=:email)")
        .param("tenant", tenant)
        .param("email", email)
        .query(Boolean.class)
        .single();
  }

  public void insert(String tenant, HireRequest r, String key, String correlation) {
    db.sql(
            "insert into hr_hire_request(id,tenant,name,email,phone,title,department,location,manager_id,start_date,reason,status,requested_by,requester_employee_id,recorded_at,idempotency_key) values(:id,:tenant,:name,:email,:phone,:title,:department,:location,:manager,:start,:reason,'SUBMITTED',:actor,:requester,:now,:key)")
        .param("id", r.id())
        .param("tenant", tenant)
        .param("name", r.name())
        .param("email", r.email())
        .param("phone", r.phone())
        .param("title", r.title())
        .param("department", r.department())
        .param("location", r.location())
        .param("manager", r.managerId())
        .param("start", r.startDate())
        .param("reason", r.reason())
        .param("actor", r.requestedBy())
        .param("requester", r.requesterEmployeeId())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .param("key", key)
        .update();
    audit(tenant, r, null, "SUBMITTED", r.requestedBy(), r.reason(), r.recordedAt(), correlation);
  }

  public void decide(
      String tenant,
      HireRequest r,
      String actor,
      String decision,
      String comment,
      Employee employee,
      Instant now,
      String correlation) {
    String employeeId = employee == null ? null : employee.id();
    if (employee != null) {
      db.sql(
              "insert into hr_employee(tenant,id,name,email,phone,title,department,location,status,manager_id,start_date,address,emergency_contact_name,emergency_contact_relationship,emergency_contact_phone) values(:tenant,:id,:name,:email,:phone,:title,:department,:location,:status,:manager,:start,:address,:contactName,:contactRelationship,:contactPhone)")
          .param("tenant", tenant)
          .param("id", employeeId)
          .param("name", employee.name())
          .param("email", employee.email())
          .param("phone", employee.phone())
          .param("title", employee.title())
          .param("department", employee.department())
          .param("location", employee.location())
          .param("manager", employee.managerId())
          .param("start", employee.startDate())
          .param("status", employee.status())
          .param("address", employee.address())
          .param("contactName", employee.emergencyContactName())
          .param("contactRelationship", employee.emergencyContactRelationship())
          .param("contactPhone", employee.emergencyContactPhone())
          .update();
      db.sql(
              "insert into hr_employee_event(tenant,event_type,employee_id,effective_date,recorded_at,correlation_id,delivery_status) values(:tenant,'EMPLOYEE_CREATED',:employee,:date,:now,:correlation,'PENDING')")
          .param("tenant", tenant)
          .param("employee", employeeId)
          .param("date", r.startDate())
          .param("now", now.atOffset(ZoneOffset.UTC))
          .param("correlation", correlation)
          .update();
    }
    db.sql(
            "update hr_hire_request set status=:status,employee_id=:employee,decided_by=:actor,comment=:comment where tenant=:tenant and id=:id")
        .param("status", decision)
        .param("employee", employeeId)
        .param("actor", actor)
        .param("comment", comment)
        .param("tenant", tenant)
        .param("id", r.id())
        .update();
    audit(tenant, r, "SUBMITTED", decision, actor, comment, now, correlation);
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) values(:tenant,:subject,:message,:now)")
        .param("tenant", tenant)
        .param("subject", r.requestedBy())
        .param(
            "message",
            "Hiring request "
                + decision.toLowerCase()
                + ". Employee setup status is available in Hiring.")
        .param("now", now.atOffset(ZoneOffset.UTC))
        .update();
  }

  private void audit(
      String tenant,
      HireRequest r,
      String oldStatus,
      String newStatus,
      String actor,
      String reason,
      Instant now,
      String correlation) {
    db.sql(
            "insert into hr_hire_audit(tenant,request_id,old_status,new_status,actor,reason,recorded_at,effective_date,correlation_id) values(:tenant,:id,:old,:new,:actor,:reason,:now,:date,:correlation)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("old", oldStatus)
        .param("new", newStatus)
        .param("actor", actor)
        .param("reason", reason)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("date", r.startDate())
        .param("correlation", correlation)
        .update();
  }

  public List<HireAudit> history(String tenant, String id) {
    return db.sql(
            "select old_status,new_status,actor,reason,recorded_at,effective_date,correlation_id from hr_hire_audit where tenant=:tenant and request_id=:id order by recorded_at,id")
        .param("tenant", tenant)
        .param("id", id)
        .query(HireAudit.class)
        .list();
  }
}
