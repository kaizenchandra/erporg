package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.ExitTaskModels.*;
import com.atlas.modules.corehr.application.port.out.ExitTaskStore;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcExitTaskStore implements ExitTaskStore {
  private final JdbcClient db;
  private static final String SELECT =
      "select id,request_id,employee_id,assignee_id,ordinal,title,instructions,due_date,reason,created_by,recorded_at,status,version,completed_by,completion_comment,completed_at from hr_exit_task where tenant=:tenant";

  private static final String ORIGINAL = SELECT;
  private static final String CURRENT =
      SELECT.replace("from hr_exit_task ", "from hr_exit_task_current ");

  public JdbcExitTaskStore(JdbcClient db) {
    this.db = db;
  }

  public boolean hasIdentity(String tenant, String employeeId) {
    return db.sql(
            "select exists(select 1 from identity_membership where tenant=:tenant and employee_id=:employee)")
        .param("tenant", tenant)
        .param("employee", employeeId)
        .query(Boolean.class)
        .single();
  }

  public Rows mine(String tenant, String assignee, Query q) {
    var params =
        Map.<String, Object>of("tenant", tenant, "assignee", assignee, "status", q.status());
    var filter = " and assignee_id=:assignee and (:status='ALL' or status=:status)";
    var total =
        db.sql("select count(*) from (" + CURRENT + filter + ") scoped")
            .params(params)
            .query(Long.class)
            .single();
    var items =
        db.sql(CURRENT + filter + " order by recorded_at desc,id limit :size offset :offset")
            .params(params)
            .param("size", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(ExitTask.class)
            .list();
    return new Rows(items, total);
  }

  public List<ExitTask> forCase(String tenant, String id) {
    return db.sql(CURRENT + " and request_id=:id order by ordinal limit 50")
        .param("tenant", tenant)
        .param("id", id)
        .query(ExitTask.class)
        .list();
  }

  public Optional<Replay> duplicate(String tenant, String subject, String key) {
    var t =
        db.sql(ORIGINAL + " and created_by=:subject and idempotency_key=:key")
            .param("tenant", tenant)
            .param("subject", subject)
            .param("key", key)
            .query(ExitTask.class)
            .optional();
    return t.map(task -> new Replay(task, task.ordinal() - 1));
  }

  public ExitTask task(String tenant, String id, boolean lock) {
    if (lock)
      db.sql("select id from hr_exit_task where tenant=:tenant and id=:id for update")
          .param("tenant", tenant)
          .param("id", id)
          .query(String.class)
          .optional()
          .orElseThrow(ChangeRequest.Denied::new);
    return db.sql(CURRENT + " and id=:id")
        .param("tenant", tenant)
        .param("id", id)
        .query(ExitTask.class)
        .optional()
        .orElseThrow(ChangeRequest.Denied::new);
  }

  public String completionKey(String tenant, String id) {
    return db.sql("select completion_key from hr_exit_task where tenant=:tenant and id=:id")
        .param("tenant", tenant)
        .param("id", id)
        .query(String.class)
        .optional()
        .orElse("");
  }

  public void insert(String tenant, ExitTask t, String key, long input, String correlation) {
    db.sql(
            "insert into hr_exit_task(tenant,id,request_id,employee_id,assignee_id,ordinal,title,instructions,due_date,reason,created_by,recorded_at,idempotency_key,input_version,status,version) values(:tenant,:id,:request,:employee,:assignee,:ordinal,:title,:instructions,:date,:reason,:actor,:now,:key,:input,'OPEN',0)")
        .param("tenant", tenant)
        .param("id", t.id())
        .param("request", t.requestId())
        .param("employee", t.employeeId())
        .param("assignee", t.assigneeId())
        .param("ordinal", t.ordinal())
        .param("title", t.title())
        .param("instructions", t.instructions())
        .param("date", t.dueDate())
        .param("reason", t.reason())
        .param("actor", t.createdBy())
        .param("now", t.recordedAt().atOffset(ZoneOffset.UTC))
        .param("key", key)
        .param("input", input)
        .update();
    audit(tenant, t, null, t.createdBy(), t.reason(), t.recordedAt(), correlation);
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) select :tenant,subject,'An exit task is assigned to you. Review My exit tasks.',:now from identity_membership where tenant=:tenant and employee_id=:assignee")
        .param("tenant", tenant)
        .param("now", t.recordedAt().atOffset(ZoneOffset.UTC))
        .param("assignee", t.assigneeId())
        .update();
  }

  public void complete(String tenant, ExitTask t, String key, String correlation) {
    int changed =
        db.sql(
                "update hr_exit_task set status='COMPLETED',version=1,completed_by=:actor,completion_comment=:comment,completed_at=:now,completion_key=:key where tenant=:tenant and id=:id and status='OPEN' and version=0")
            .param("actor", t.completedBy())
            .param("comment", t.completionComment())
            .param("now", t.completedAt().atOffset(ZoneOffset.UTC))
            .param("key", key)
            .param("tenant", tenant)
            .param("id", t.id())
            .update();
    if (changed != 1) throw new ChangeRequest.Conflict("Task changed. Refresh and retry.");
    audit(tenant, t, "OPEN", t.completedBy(), t.completionComment(), t.completedAt(), correlation);
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) values(:tenant,:subject,'An assigned exit task was completed. Review the case tasks.',:now)")
        .param("tenant", tenant)
        .param("subject", t.createdBy())
        .param("now", t.completedAt().atOffset(ZoneOffset.UTC))
        .update();
  }

  private void audit(
      String tenant,
      ExitTask t,
      String old,
      String actor,
      String reason,
      Instant now,
      String correlation) {
    db.sql(
            "insert into hr_exit_task_audit(tenant,task_id,old_status,new_status,actor,reason,effective_date,recorded_at,correlation_id) values(:tenant,:id,:old,:status,:actor,:reason,:date,:now,:correlation)")
        .param("tenant", tenant)
        .param("id", t.id())
        .param("old", old)
        .param("status", t.status())
        .param("actor", actor)
        .param("reason", reason)
        .param("date", t.dueDate())
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("correlation", correlation)
        .update();
  }

  public List<Audit> history(String tenant, String id) {
    return db.sql(
            "select old_status,new_status,actor,reason,effective_date,recorded_at,correlation_id from hr_exit_task_audit where tenant=:tenant and task_id=:id order by recorded_at,id")
        .param("tenant", tenant)
        .param("id", id)
        .query(Audit.class)
        .list();
  }

  private static final String MANAGEMENT =
      "select task_id,ordinal,action,old_assignee_id,new_assignee_id,old_status,new_status,actor,actor_employee_id,reason,input_version,recorded_at,correlation_id from hr_exit_task_management where tenant=:tenant";

  public Optional<Management> managementReplay(String tenant, String subject, String key) {
    return db.sql(MANAGEMENT + " and actor=:subject and idempotency_key=:key")
        .param("tenant", tenant)
        .param("subject", subject)
        .param("key", key)
        .query(Management.class)
        .optional();
  }

  public List<Management> managementHistory(String tenant, String id) {
    return db.sql(MANAGEMENT + " and task_id=:id order by ordinal limit 100")
        .param("tenant", tenant)
        .param("id", id)
        .query(Management.class)
        .list();
  }

  public void manage(String tenant, Management e, String key) {
    db.sql(
            "insert into hr_exit_task_management(tenant,task_id,ordinal,action,old_assignee_id,new_assignee_id,old_status,new_status,actor,actor_employee_id,reason,input_version,idempotency_key,recorded_at,correlation_id) values(:tenant,:id,:ordinal,:action,:old,:new,:oldStatus,:newStatus,:actor,:employee,:reason,:input,:key,:now,:correlation)")
        .param("tenant", tenant)
        .param("id", e.taskId())
        .param("ordinal", e.ordinal())
        .param("action", e.action())
        .param("old", e.oldAssigneeId())
        .param("new", e.newAssigneeId())
        .param("oldStatus", e.oldStatus())
        .param("newStatus", e.newStatus())
        .param("actor", e.actor())
        .param("employee", e.actorEmployeeId())
        .param("reason", e.reason())
        .param("input", e.inputVersion())
        .param("key", key)
        .param("now", e.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", e.correlationId())
        .update();
    notifyEmployee(
        tenant,
        e.oldAssigneeId(),
        "An exit task assignment changed. Refresh My exit tasks.",
        e.recordedAt());
    if (!e.oldAssigneeId().equals(e.newAssigneeId()))
      notifyEmployee(
          tenant,
          e.newAssigneeId(),
          "An exit task is assigned to you. Review My exit tasks.",
          e.recordedAt());
  }

  private void notifyEmployee(String tenant, String employee, String message, Instant now) {
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) select :tenant,subject,:message,:now from identity_membership where tenant=:tenant and employee_id=:employee")
        .param("tenant", tenant)
        .param("employee", employee)
        .param("message", message)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .update();
  }
}
