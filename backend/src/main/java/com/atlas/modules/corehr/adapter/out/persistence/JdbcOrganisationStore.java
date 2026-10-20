package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.CreateDepartment;
import com.atlas.modules.corehr.application.model.CreatePosition;
import com.atlas.modules.corehr.application.model.DepartmentAudit;
import com.atlas.modules.corehr.application.model.PositionAudit;
import com.atlas.modules.corehr.application.model.PositionListing;
import com.atlas.modules.corehr.application.model.PositionPage;
import com.atlas.modules.corehr.application.model.PositionQuery;
import com.atlas.modules.corehr.application.port.out.OrganisationStore;
import com.atlas.modules.corehr.domain.model.Department;
import com.atlas.modules.corehr.domain.model.Position;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcOrganisationStore implements OrganisationStore {
  private final JdbcClient db;
  private static final String SELECT =
      "select code,name,hr_department_parent(tenant,code,(current_timestamp at time zone 'UTC')::date) parent_code,cost_centre,effective_from from hr_department where tenant=:tenant";

  public JdbcOrganisationStore(JdbcClient db) {
    this.db = db;
  }

  public void lockHierarchy(String tenant) {
    db.sql(
            "select pg_advisory_xact_lock(hashtextextended(current_schema()||'/department-hierarchy/'||:tenant,0))")
        .param("tenant", tenant)
        .query((r, n) -> 0)
        .single();
  }

  public PositionPage positions(String tenant, PositionQuery query) {
    String where =
        " from hr_position where tenant=:tenant and (:department='' or department_code=:department) and (:search='' or strpos(lower(code || ' ' || title),lower(:search))>0)";
    long total =
        db.sql("select count(*)" + where)
            .param("tenant", tenant)
            .param("department", query.departmentCode())
            .param("search", query.search())
            .query(Long.class)
            .single();
    var items =
        db.sql(
                "select code,title,department_code,(select d.name from hr_department d where d.tenant=hr_position.tenant and d.code=hr_position.department_code) department_name,effective_from"
                    + where
                    + " order by title "
                    + query.direction()
                    + ",code asc limit :size offset :offset")
            .param("tenant", tenant)
            .param("department", query.departmentCode())
            .param("search", query.search())
            .param("size", query.size())
            .param("offset", (long) query.page() * query.size())
            .query(PositionListing.class)
            .list();
    return new PositionPage(items, total, query.page(), query.size());
  }

  public Optional<CreatePosition> duplicatePosition(String tenant, String subject, String key) {
    return db.sql(
            "select p.code,p.title,p.department_code,p.effective_from,a.reason,p.idempotency_key from hr_position p join hr_position_audit a on a.tenant=p.tenant and a.code=p.code where p.tenant=:tenant and p.created_by=:actor and p.idempotency_key=:key")
        .param("tenant", tenant)
        .param("actor", subject)
        .param("key", key)
        .query(CreatePosition.class)
        .optional();
  }

  public void insertPosition(
      String tenant,
      String subject,
      Position position,
      String reason,
      String key,
      Instant now,
      String correlation) {
    db.sql(
            "insert into hr_position(tenant,code,title,department_code,effective_from,created_by,idempotency_key) values(:tenant,:code,:title,:department,:date,:actor,:key)")
        .param("tenant", tenant)
        .param("code", position.code())
        .param("title", position.title())
        .param("department", position.departmentCode())
        .param("date", position.effectiveFrom())
        .param("actor", subject)
        .param("key", key)
        .update();
    db.sql(
            "insert into hr_position_audit(tenant,code,actor,reason,recorded_at,effective_from,correlation_id) values(:tenant,:code,:actor,:reason,:now,:date,:correlation)")
        .param("tenant", tenant)
        .param("code", position.code())
        .param("actor", subject)
        .param("reason", reason)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("date", position.effectiveFrom())
        .param("correlation", correlation)
        .update();
  }

  public List<PositionAudit> positionHistory(String tenant) {
    return db.sql(
            "select code,actor,reason,recorded_at,effective_from,correlation_id from hr_position_audit where tenant=:tenant order by recorded_at desc,id desc limit 100")
        .param("tenant", tenant)
        .query(PositionAudit.class)
        .list();
  }

  public List<Department> departments(String tenant) {
    return db.sql(SELECT + " order by name limit 1000")
        .param("tenant", tenant)
        .query(Department.class)
        .list();
  }

  public Optional<Position> position(String tenant, String code) {
    return db.sql(
            "select code,title,department_code,effective_from from hr_position where tenant=:tenant and code=:code")
        .param("tenant", tenant)
        .param("code", code)
        .query(Position.class)
        .optional();
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
