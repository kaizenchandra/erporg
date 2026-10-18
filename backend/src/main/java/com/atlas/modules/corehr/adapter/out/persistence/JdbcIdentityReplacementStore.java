package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.port.out.IdentityReplacementStore;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcIdentityReplacementStore implements IdentityReplacementStore {
  private final JdbcClient db;

  public JdbcIdentityReplacementStore(JdbcClient db) {
    this.db = db;
  }

  private static final String SELECT =
      "select id,employee_id,predecessor_id,subject,reason,input_version,actor,actor_employee_id,recorded_at,correlation_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id from hr_identity_replacement_current a where tenant=:tenant";

  public List<String> candidates(String tenant, String employee) {
    return db.sql(
            "select subject from identity_membership m where tenant=:tenant and employee_id=:employee and not exists(select 1 from hr_local_access_grant g where g.subject=m.subject) and not exists(select 1 from hr_rehire_access_revocation x where x.subject=m.subject) order by subject limit 20")
        .param("tenant", tenant)
        .param("employee", employee)
        .query(String.class)
        .list();
  }

  public List<IdentityReplacement> history(String tenant, String employee) {
    return db.sql(SELECT + " and employee_id=:employee order by recorded_at,id limit 20")
        .param("tenant", tenant)
        .param("employee", employee)
        .query(IdentityReplacement.class)
        .list();
  }

  public Optional<IdentityReplacement> duplicate(
      String tenant, String actor, String key, boolean review) {
    String clause =
        review
            ? " and exists(select 1 from hr_identity_replacement_decision d where d.tenant=a.tenant and d.replacement_id=a.id and d.reviewer=:actor and d.idempotency_key=:key)"
            : " and exists(select 1 from hr_identity_replacement q where q.tenant=a.tenant and q.id=a.id and q.actor=:actor and q.idempotency_key=:key)";
    return db.sql(SELECT + clause)
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query(IdentityReplacement.class)
        .optional();
  }

  public void submit(String tenant, IdentityReplacement r, String key) {
    db.sql(
            "insert into hr_identity_replacement(tenant,id,predecessor_id,employee_id,subject,reason,input_version,actor,actor_employee_id,recorded_at,correlation_id,idempotency_key) values(:tenant,:id,:rehire,:employee,:subject,:reason,:version,:actor,:actorEmployee,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("rehire", r.predecessorId())
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

  public void review(String tenant, IdentityReplacement r, String key, String epoch) {
    if (r.status().equals("APPROVED")) {
      int n =
          db.sql(
                  "update hr_employee set version=version+1 where tenant=:tenant and id=:employee and version=:v")
              .param("tenant", tenant)
              .param("employee", r.employeeId())
              .param("v", r.reviewVersion())
              .update();
      if (n != 1) throw new ChangeRequest.Conflict("Employee changed. Refresh context.");
    }
    db.sql(
            "insert into hr_identity_replacement_decision(tenant,replacement_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id,idempotency_key) values(:tenant,:id,:status,:actor,:employee,:reason,:version,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("status", r.status())
        .param("actor", r.reviewer())
        .param("employee", r.reviewerEmployeeId())
        .param("reason", r.comment())
        .param("version", r.reviewVersion())
        .param("now", r.reviewedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", r.reviewCorrelationId())
        .param("key", key)
        .update();
    if (r.status().equals("APPROVED"))
      db.sql(
              "insert into hr_local_access_grant(tenant,id,employee_id,subject,role,epoch,ordinal,predecessor_id,effective_date,recorded_at,correlation_id) values(:tenant,:id,:employee,:subject,'EMPLOYEE',:epoch,2,:predecessor,:date,:now,:correlation)")
          .param("tenant", tenant)
          .param("id", r.id())
          .param("employee", r.employeeId())
          .param("subject", r.subject())
          .param("epoch", epoch)
          .param("predecessor", r.predecessorId())
          .param("date", r.reviewedAt().atZone(ZoneOffset.UTC).toLocalDate())
          .param("now", r.reviewedAt().atOffset(ZoneOffset.UTC))
          .param("correlation", r.reviewCorrelationId())
          .update();
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) select :tenant,subject,:message,:now from identity_membership where tenant=:tenant and role='HR' and employee_id<>:employee")
        .param("tenant", tenant)
        .param("employee", r.employeeId())
        .param(
            "message",
            r.status().equals("APPROVED")
                ? "Replacement Employee-only local access was approved. Fresh sign-in required; old identity remains held."
                : "Identity replacement was rejected. Access remains unchanged.")
        .param("now", r.reviewedAt().atOffset(ZoneOffset.UTC))
        .update();
  }
}
