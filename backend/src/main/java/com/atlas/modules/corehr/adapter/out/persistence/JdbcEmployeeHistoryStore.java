package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.EmployeeHistoryModels.*;
import com.atlas.modules.corehr.application.port.out.EmployeeHistoryStore;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcEmployeeHistoryStore implements EmployeeHistoryStore {
  private final JdbcClient db;

  public JdbcEmployeeHistoryStore(JdbcClient db) {
    this.db = db;
  }

  private static final String FROM =
      " from hr_audit a join hr_request r on r.tenant=a.tenant and r.id=a.request_id where a.tenant=:tenant and a.employee_id=:employee and a.field in(:fields) and(:field='ALL' or a.field=:field) and(:action='ALL' or a.action=:action)";

  private Map<String, Object> parameters(Scope s, Query q) {
    return Map.of(
        "tenant",
        s.tenant(),
        "employee",
        s.employeeId(),
        "fields",
        s.fields(),
        "field",
        q.field(),
        "action",
        q.action());
  }

  public long watermark(Scope s, Query q) {
    return db.sql("select coalesce(max(a.id),0)" + FROM)
        .params(parameters(s, q))
        .query(Long.class)
        .single();
  }

  public long count(Scope s, Query q, long through) {
    return db.sql("select count(*)" + FROM + " and a.id<=:through")
        .params(parameters(s, q))
        .param("through", through)
        .query(Long.class)
        .single();
  }

  public boolean anchorExists(Scope s, Query q) {
    var c = q.cursor();
    return db.sql(
            "select exists(select 1"
                + FROM
                + " and a.id=:id and a.id<=:through and a.recorded_at=:at)")
        .params(parameters(s, q))
        .param("id", c.beforeId())
        .param("through", c.throughId())
        .param("at", c.beforeRecordedAt().atOffset(ZoneOffset.UTC))
        .query(Boolean.class)
        .single();
  }

  public List<Item> read(Scope s, Query q, long through, int limit) {
    String direction = q.order().equals("asc") ? "asc" : "desc";
    String after =
        q.cursor() == null
            ? ""
            : " and (a.recorded_at,a.id) " + (q.order().equals("asc") ? ">" : "<") + " (:at,:id)";
    var sql =
        db.sql(
                "select a.id,a.action,a.field,a.old_value,a.new_value,a.reason,a.actor,a.recorded_at,a.correlation_id,r.effective_date"
                    + FROM
                    + " and a.id<=:through"
                    + after
                    + " order by a.recorded_at "
                    + direction
                    + ",a.id "
                    + direction
                    + " limit :limit")
            .params(parameters(s, q))
            .param("through", through)
            .param("limit", limit);
    if (q.cursor() != null)
      sql =
          sql.param("at", q.cursor().beforeRecordedAt().atOffset(ZoneOffset.UTC))
              .param("id", q.cursor().beforeId());
    return sql.query(
            (r, n) ->
                new Item(
                    r.getString("id"),
                    r.getString("action"),
                    r.getString("field"),
                    r.getString("old_value"),
                    r.getString("new_value"),
                    r.getString("reason"),
                    r.getString("actor"),
                    r.getTimestamp("recorded_at").toInstant(),
                    r.getString("correlation_id"),
                    r.getObject("effective_date", LocalDate.class)))
        .list();
  }
}
