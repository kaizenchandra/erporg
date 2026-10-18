package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.port.out.RehireAccessStore;
import com.atlas.modules.corehr.domain.model.*;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcRehireAccessStore implements RehireAccessStore {
  private final JdbcClient db;

  public JdbcRehireAccessStore(JdbcClient db) {
    this.db = db;
  }

  private static final String SELECT =
      "select activation_id,employee_id,subject,input_version,new_version,actor,actor_employee_id,reason,recorded_at,correlation_id from hr_rehire_access_revocation where tenant=:tenant";

  public Optional<com.atlas.modules.corehr.application.model.RehireAccessModels.Grant> grant(
      String tenant, String employee) {
    return db.sql(
            "select id activation_id,employee_id,subject,role,ordinal grant_number,recorded_at from hr_local_access_current where tenant=:tenant and employee_id=:employee")
        .param("tenant", tenant)
        .param("employee", employee)
        .query(com.atlas.modules.corehr.application.model.RehireAccessModels.Grant.class)
        .optional();
  }

  public java.util.List<com.atlas.modules.corehr.application.model.RehireAccessModels.Grant> grants(
      String tenant, String employee) {
    return db.sql(
            "select id activation_id,employee_id,subject,role,ordinal grant_number,recorded_at from hr_local_access_grant where tenant=:tenant and employee_id=:employee order by ordinal limit 2")
        .param("tenant", tenant)
        .param("employee", employee)
        .query(com.atlas.modules.corehr.application.model.RehireAccessModels.Grant.class)
        .list();
  }

  public java.util.List<RehireAccessRevocation> revocations(String tenant, String employee) {
    return db.sql(SELECT + " and employee_id=:employee order by input_version limit 2")
        .param("tenant", tenant)
        .param("employee", employee)
        .query(RehireAccessRevocation.class)
        .list();
  }

  public Optional<RehireAccessRevocation> revocation(String tenant, String employee) {
    return db.sql(
            SELECT
                + " and employee_id=:employee and activation_id=(select id from hr_local_access_current where tenant=:tenant and employee_id=:employee)")
        .param("tenant", tenant)
        .param("employee", employee)
        .query(RehireAccessRevocation.class)
        .optional();
  }

  public Optional<RehireAccessRevocation> duplicate(String tenant, String actor, String key) {
    return db.sql(SELECT + " and actor=:actor and idempotency_key=:key")
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query(RehireAccessRevocation.class)
        .optional();
  }

  public void revoke(String tenant, RehireAccessRevocation r, String key) {
    int n =
        db.sql(
                "update hr_employee set version=version+1 where tenant=:tenant and id=:employee and version=:version")
            .param("tenant", tenant)
            .param("employee", r.employeeId())
            .param("version", r.inputVersion())
            .update();
    if (n != 1) throw new ChangeRequest.Conflict("Employee changed. Refresh access context.");
    db.sql(
            "insert into hr_rehire_access_revocation(tenant,activation_id,employee_id,subject,input_version,new_version,actor,actor_employee_id,reason,recorded_at,correlation_id,idempotency_key) values(:tenant,:id,:employee,:subject,:input,:new,:actor,:actorEmployee,:reason,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", r.activationId())
        .param("employee", r.employeeId())
        .param("subject", r.subject())
        .param("input", r.inputVersion())
        .param("new", r.newVersion())
        .param("actor", r.actor())
        .param("actorEmployee", r.actorEmployeeId())
        .param("reason", r.reason())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", r.correlationId())
        .param("key", key)
        .update();
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) select :tenant,subject,'A fresh rehire local access grant was revoked. External provider action remains separate.',:now from identity_membership where tenant=:tenant and role='HR' and employee_id<>:employee")
        .param("tenant", tenant)
        .param("employee", r.employeeId())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .update();
  }
}
