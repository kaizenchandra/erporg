package com.atlas.modules.documents.adapter.out.persistence;

import com.atlas.modules.documents.application.model.RetentionEvent;
import com.atlas.modules.documents.application.port.out.DocumentRetentionStore;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcDocumentRetentionStore implements DocumentRetentionStore {
  private final JdbcClient db;

  public JdbcDocumentRetentionStore(JdbcClient db) {
    this.db = db;
  }

  private RetentionEvent row(ResultSet r, int n) throws SQLException {
    return new RetentionEvent(
        r.getString("root_id"),
        r.getInt("version"),
        r.getString("action"),
        r.getDate("retain_until") == null ? null : r.getDate("retain_until").toLocalDate(),
        r.getString("basis"),
        r.getString("hold_id"),
        r.getString("case_reference"),
        r.getString("actor"),
        r.getString("actor_employee_id"),
        r.getString("reason"),
        r.getTimestamp("recorded_at").toInstant(),
        r.getString("correlation_id"));
  }

  public List<RetentionEvent> history(String tenant, String root) {
    return db.sql(
            "select * from document_retention_event where tenant=:t and root_id=:root order by version desc limit 101")
        .param("t", tenant)
        .param("root", root)
        .query(this::row)
        .list();
  }

  public Optional<RetentionEvent> duplicate(String tenant, String actor, String key) {
    return db.sql(
            "select * from document_retention_event where tenant=:t and actor=:a and idempotency_key=:k")
        .param("t", tenant)
        .param("a", actor)
        .param("k", key)
        .query(this::row)
        .optional();
  }

  public void append(String tenant, String employeeId, RetentionEvent e, String key) {
    db.sql(
            "insert into document_retention_event(tenant,root_id,employee_id,version,action,retain_until,basis,hold_id,case_reference,actor,actor_employee_id,reason,recorded_at,correlation_id,idempotency_key) values(:t,:root,:emp,:v,:action,:date,:basis,:hold,:case,:a,:actorEmp,:reason,:at,:corr,:key)")
        .param("t", tenant)
        .param("root", e.rootId())
        .param("emp", employeeId)
        .param("v", e.version())
        .param("action", e.action())
        .param("date", e.retainUntil())
        .param("basis", e.basis())
        .param("hold", e.holdId())
        .param("case", e.caseReference())
        .param("a", e.actor())
        .param("actorEmp", e.actorEmployeeId())
        .param("reason", e.reason())
        .param("at", Timestamp.from(e.recordedAt()))
        .param("corr", e.correlationId())
        .param("key", key)
        .update();
  }
}
