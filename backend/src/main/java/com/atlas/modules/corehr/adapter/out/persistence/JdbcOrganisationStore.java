package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.CreateDepartment;
import com.atlas.modules.corehr.application.model.DepartmentAudit;
import com.atlas.modules.corehr.application.port.out.OrganisationStore;
import com.atlas.modules.corehr.domain.model.Department;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcOrganisationStore implements OrganisationStore {
  private final JdbcClient db;
  private static final String SELECT =
      "select code,name,parent_code,cost_centre,effective_from from hr_department where tenant=:tenant";

  public JdbcOrganisationStore(JdbcClient db) {
    this.db = db;
  }

  public List<Department> departments(String tenant) {
    return db.sql(SELECT + " order by name limit 1000")
        .param("tenant", tenant)
        .query(Department.class)
        .list();
  }

  public Optional<Department> department(String tenant, String code) {
    return db.sql(SELECT + " and code=:code")
        .param("tenant", tenant)
        .param("code", code)
        .query(Department.class)
        .optional();
  }

  public Optional<Department> byName(String tenant, String name) {
    return db.sql(SELECT + " and name=:name")
        .param("tenant", tenant)
        .param("name", name)
        .query(Department.class)
        .optional();
  }

  public Optional<CreateDepartment> duplicate(String tenant, String subject, String key) {
    return db.sql(
            "select d.code,d.name,d.parent_code,d.cost_centre,d.effective_from,a.reason,d.idempotency_key from hr_department d join hr_department_audit a on a.tenant=d.tenant and a.code=d.code where d.tenant=:tenant and d.created_by=:subject and d.idempotency_key=:key")
        .param("tenant", tenant)
        .param("subject", subject)
        .param("key", key)
        .query(CreateDepartment.class)
        .optional();
  }

  public void insert(
      String tenant,
      String subject,
      Department d,
      String reason,
      String key,
      Instant now,
      String correlation) {
    db.sql(
            "insert into hr_department(tenant,code,name,parent_code,cost_centre,effective_from,created_by,idempotency_key) values(:tenant,:code,:name,:parent,:cost,:date,:actor,:key)")
        .param("tenant", tenant)
        .param("code", d.code())
        .param("name", d.name())
        .param("parent", d.parentCode())
        .param("cost", d.costCentre())
        .param("date", d.effectiveFrom())
        .param("actor", subject)
        .param("key", key)
        .update();
    db.sql(
            "insert into hr_department_audit(tenant,code,actor,reason,recorded_at,effective_from,correlation_id) values(:tenant,:code,:actor,:reason,:now,:date,:correlation)")
        .param("tenant", tenant)
        .param("code", d.code())
        .param("actor", subject)
        .param("reason", reason)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("date", d.effectiveFrom())
        .param("correlation", correlation)
        .update();
  }

  public List<DepartmentAudit> history(String tenant) {
    return db.sql(
            "select code,actor,reason,recorded_at,effective_from,correlation_id from hr_department_audit where tenant=:tenant order by recorded_at desc limit 100")
        .param("tenant", tenant)
        .query(DepartmentAudit.class)
        .list();
  }
}
