package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.DepartmentParentModels.*;
import com.atlas.modules.corehr.application.port.out.DepartmentParentStore;
import com.atlas.modules.corehr.domain.model.DepartmentHierarchy.Node;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcDepartmentParentStore implements DepartmentParentStore {
  private final JdbcClient db;
  private static final String COLUMNS =
      "code,version,old_parent_code,parent_code,effective_date,recorded_at,actor,reason,idempotency_key,correlation_id";

  public JdbcDepartmentParentStore(JdbcClient db) {
    this.db = db;
  }

  public Optional<LocalDate> latestDate(String tenant) {
    return db.sql(
            "select max(effective_date) from hr_department_parent_change where tenant=:tenant")
        .param("tenant", tenant)
        .query(LocalDate.class)
        .optional();
  }

  public void lock(String tenant) {
    db.sql(
            "select pg_advisory_xact_lock(hashtextextended(current_schema()||'/department-hierarchy/'||:tenant,0))")
        .param("tenant", tenant)
        .query((r, n) -> 0)
        .single();
  }

  public Optional<Snapshot> find(String tenant, String code, LocalDate date) {
    return db.sql(
            "select d.code,d.name,hr_department_parent(d.tenant,d.code,:date) parent_code,(select p.name from hr_department p where p.tenant=d.tenant and p.code=hr_department_parent(d.tenant,d.code,:date)) parent_name,d.effective_from,coalesce((select max(c.version) from hr_department_parent_change c where c.tenant=d.tenant and c.code=d.code),0) version from hr_department d where d.tenant=:tenant and d.code=:code")
        .param("tenant", tenant)
        .param("code", code)
        .param("date", date)
        .query(Snapshot.class)
        .optional();
  }

  public List<Node> graph(String tenant, LocalDate date) {
    return db.sql(
            "select code,hr_department_parent(tenant,code,:date) parent_code,effective_from from hr_department where tenant=:tenant")
        .param("tenant", tenant)
        .param("date", date)
        .query(Node.class)
        .list();
  }

  public Optional<Change> duplicate(String tenant, String actor, String key) {
    return db.sql(
            "select "
                + COLUMNS
                + " from hr_department_parent_change where tenant=:tenant and actor=:actor and idempotency_key=:key")
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query(Change.class)
        .optional();
  }

  public List<Change> history(String tenant, String code, int page, int size) {
    return db.sql(
            "select "
                + COLUMNS
                + " from hr_department_parent_change where tenant=:tenant and code=:code order by version desc limit :size offset :offset")
        .param("tenant", tenant)
        .param("code", code)
        .param("size", size)
        .param("offset", (long) page * size)
        .query(Change.class)
        .list();
  }

  public long total(String tenant, String code) {
    return db.sql(
            "select count(*) from hr_department_parent_change where tenant=:tenant and code=:code")
        .param("tenant", tenant)
        .param("code", code)
        .query(Long.class)
        .single();
  }

  public void insert(String tenant, Change c) {
    db.sql(
            "insert into hr_department_parent_change(tenant,"
                + COLUMNS
                + ") values(:tenant,:code,:version,:old,:parent,:date,:now,:actor,:reason,:key,:correlation)")
        .param("tenant", tenant)
        .param("code", c.code())
        .param("version", c.version())
        .param("old", c.oldParentCode())
        .param("parent", c.parentCode())
        .param("date", c.effectiveDate())
        .param("now", c.recordedAt().atOffset(ZoneOffset.UTC))
        .param("actor", c.actor())
        .param("reason", c.reason())
        .param("key", c.idempotencyKey())
        .param("correlation", c.correlationId())
        .update();
  }
}
