package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.CalendarRetirementModels.Entry;
import com.atlas.modules.corehr.application.port.out.CalendarRetirementStore;
import com.atlas.modules.corehr.domain.model.CalendarRetirement;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcCalendarRetirementStore implements CalendarRetirementStore {
  private final JdbcClient db;

  public JdbcCalendarRetirementStore(JdbcClient db) {
    this.db = db;
  }

  private Entry entry(ResultSet r) throws SQLException {
    return new Entry(
        new CalendarRetirement(
            r.getString("code"),
            r.getInt("calendar_version"),
            r.getObject("effective_date", LocalDate.class)),
        r.getString("created_by"),
        r.getString("reason"),
        r.getTimestamp("recorded_at").toInstant(),
        r.getString("correlation_id"));
  }

  public Optional<Entry> find(String tenant, String code) {
    return db.sql("select * from hr_work_calendar_retirement where tenant=:tenant and code=:code")
        .param("tenant", tenant)
        .param("code", code)
        .query((r, n) -> entry(r))
        .optional();
  }

  public Optional<Entry> duplicate(String tenant, String actor, String key) {
    return db.sql(
            "select * from hr_work_calendar_retirement where tenant=:tenant and created_by=:actor and idempotency_key=:key")
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query((r, n) -> entry(r))
        .optional();
  }

  public void append(String tenant, Entry e, String key) {
    var r = e.retirement();
    db.sql(
            "insert into hr_work_calendar_retirement(tenant,code,calendar_version,effective_date,created_by,reason,recorded_at,correlation_id,idempotency_key) values(:tenant,:code,:version,:date,:actor,:reason,:at,:correlation,:key)")
        .param("tenant", tenant)
        .param("code", r.code())
        .param("version", r.version())
        .param("date", r.effectiveDate())
        .param("actor", e.createdBy())
        .param("reason", e.reason())
        .param("at", Timestamp.from(e.recordedAt()))
        .param("correlation", e.correlationId())
        .param("key", key)
        .update();
  }
}
