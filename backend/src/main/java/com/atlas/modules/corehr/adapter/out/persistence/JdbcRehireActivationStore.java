package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.RehireActivationModels.Completion;
import com.atlas.modules.corehr.application.port.out.RehireActivationStore;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcRehireActivationStore implements RehireActivationStore {
  private final JdbcClient db;

  public JdbcRehireActivationStore(JdbcClient db) {
    this.db = db;
  }

  private static final String SELECT =
      "select id,rehire_id,employee_id,subject,reason,input_version,actor,actor_employee_id,recorded_at,correlation_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id from hr_rehire_activation_current a where tenant=:tenant";

  public List<String> identities(String tenant, String employee) {
    var rows =
        db.sql(
                "select subject from identity_membership m where tenant=:tenant and employee_id=:employee and not exists(select 1 from hr_local_access_grant g where g.subject=m.subject and (g.tenant<>m.tenant or g.employee_id<>m.employee_id)) order by subject limit 21")
            .param("tenant", tenant)
            .param("employee", employee)
            .query(String.class)
            .list();
    if (rows.size() > 20)
      throw new ChangeRequest.Conflict(
          "More than twenty identity mappings require review by identity operations.");
    return rows;
  }

  public List<RehireActivation> history(String tenant, String employee) {
    return db.sql(SELECT + " and employee_id=:employee order by recorded_at,id")
        .param("tenant", tenant)
        .param("employee", employee)
        .query(RehireActivation.class)
        .list();
  }

  public Optional<RehireActivation> duplicate(
      String tenant, String actor, String key, boolean review) {
    String clause =
        review
            ? " and exists(select 1 from hr_rehire_activation_decision d where d.tenant=a.tenant and d.activation_id=a.id and d.reviewer=:actor and d.idempotency_key=:key)"
            : " and exists(select 1 from hr_rehire_activation q where q.tenant=a.tenant and q.id=a.id and q.actor=:actor and q.idempotency_key=:key)";
    return db.sql(SELECT + clause)
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query(RehireActivation.class)
        .optional();
  }

  public Optional<Completion> completion(String tenant, String employee) {
    return db.sql(
            "select activation_id,rehire_id,employee_id,subject,role,effective_date,input_version,new_version,actor,reason,recorded_at,correlation_id from hr_rehire_completion where tenant=:tenant and employee_id=:employee")
        .param("tenant", tenant)
        .param("employee", employee)
        .query(Completion.class)
        .optional();
  }

  public void submit(String tenant, RehireActivation r, String key) {
    db.sql(
            "insert into hr_rehire_activation(tenant,id,rehire_id,employee_id,subject,reason,input_version,actor,actor_employee_id,recorded_at,correlation_id,idempotency_key) values(:tenant,:id,:rehire,:employee,:subject,:reason,:version,:actor,:actorEmployee,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("rehire", r.rehireId())
        .param("employee", r.employeeId())
        .param("subject", r.subject())
        .param("reason", r.reason())
        .param("version", r.inputVersion())
        .param("actor", r.actor())
        .param("actorEmployee", r.actorEmployeeId())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", r.correlationId())
        .param("key", key)
        .update();
  }

  public void review(String tenant, RehireActivation r, String key, String epoch) {
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
            "insert into hr_rehire_activation_decision(tenant,activation_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id,idempotency_key) values(:tenant,:id,:status,:reviewer,:reviewerEmployee,:comment,:version,:now,:correlation,:key)")
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
    if (r.status().equals("APPROVED")) {
      db.sql(
              "insert into hr_rehire_completion(tenant,activation_id,rehire_id,employee_id,subject,role,effective_date,input_version,new_version,actor,reason,recorded_at,correlation_id) values(:tenant,:id,:rehire,:employee,:subject,'EMPLOYEE',:date,:input,:new,:actor,:reason,:now,:correlation)")
          .param("tenant", tenant)
          .param("id", r.id())
          .param("rehire", r.rehireId())
          .param("employee", r.employeeId())
          .param("subject", r.subject())
          .param("date", r.reviewedAt().atZone(ZoneOffset.UTC).toLocalDate())
          .param("input", r.reviewVersion())
          .param("new", r.reviewVersion() + 1)
          .param("actor", r.reviewer())
          .param("reason", r.comment())
          .param("now", r.reviewedAt().atOffset(ZoneOffset.UTC))
          .param("correlation", r.reviewCorrelationId())
          .update();
      db.sql(
              "insert into hr_rehire_access(tenant,employee_id,activation_id,subject,role,epoch,effective_date,recorded_at,correlation_id) values(:tenant,:employee,:id,:subject,'EMPLOYEE',:epoch,:date,:now,:correlation)")
          .param("tenant", tenant)
          .param("employee", r.employeeId())
          .param("id", r.id())
          .param("subject", r.subject())
          .param("epoch", epoch)
          .param("date", r.reviewedAt().atZone(ZoneOffset.UTC).toLocalDate())
          .param("now", r.reviewedAt().atOffset(ZoneOffset.UTC))
          .param("correlation", r.reviewCorrelationId())
          .update();
    }
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) select :tenant,subject,:message,:now from identity_membership where tenant=:tenant and role='HR' and employee_id<>:employee")
        .param("tenant", tenant)
        .param("employee", r.employeeId())
        .param(
            "message",
            r.status().equals("APPROVED")
                ? "Rehire onboarding and Employee-only local access were approved. Fresh sign-in required; provider/payroll steps remain separate."
                : "Rehire onboarding/access request was rejected. Access remains unchanged.")
        .param("now", r.reviewedAt().atOffset(ZoneOffset.UTC))
        .update();
  }
}
