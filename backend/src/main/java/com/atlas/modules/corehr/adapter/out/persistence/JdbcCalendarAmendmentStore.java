package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.CalendarAmendmentModels.*;
import com.atlas.modules.corehr.application.port.out.CalendarAmendmentStore;
import com.atlas.modules.corehr.domain.model.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcCalendarAmendmentStore implements CalendarAmendmentStore {
  private final JdbcClient db;

  public JdbcCalendarAmendmentStore(JdbcClient db) {
    this.db = db;
  }

  private RevisionEntry entry(ResultSet r) throws SQLException {
    var base =
        new WorkCalendar(
            r.getString("code"),
            r.getString("name"),
            r.getString("time_zone"),
            r.getObject("effective_from", LocalDate.class),
            r.getObject("effective_until", LocalDate.class),
            Arrays.stream((Object[]) r.getArray("working_days").getArray())
                .map(v -> ((Number) v).intValue())
                .toList(),
            Arrays.stream((Object[]) r.getArray("closed_dates").getArray())
                .map(v -> ((java.sql.Date) v).toLocalDate())
                .toList());
    return new RevisionEntry(
        new CalendarRevision(
            r.getInt("ordinal"), r.getObject("effective_date", LocalDate.class), base),
        r.getString("created_by"),
        r.getString("reason"),
        r.getTimestamp("recorded_at").toInstant(),
        r.getString("correlation_id"));
  }

  private static final String SELECT =
      "select r.*,b.name,b.time_zone,b.effective_from,b.effective_until from hr_work_calendar_revision r join hr_work_calendar b on b.tenant=r.tenant and b.code=r.code ";

  public List<RevisionEntry> history(String tenant, WorkCalendar base) {
    return db.sql(SELECT + "where r.tenant=:tenant and r.code=:code order by ordinal limit 21")
        .param("tenant", tenant)
        .param("code", base.code())
        .query((r, n) -> entry(r))
        .list();
  }

  public Optional<RevisionEntry> duplicate(String tenant, String actor, String key) {
    return db.sql(
            SELECT + "where r.tenant=:tenant and r.created_by=:actor and r.idempotency_key=:key")
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query((r, n) -> entry(r))
        .optional();
  }

  public void append(String tenant, RevisionEntry e, String key) {
    var r = e.revision();
    var p = r.policy();
    db.sql(
            "insert into hr_work_calendar_revision(tenant,code,ordinal,effective_date,working_days,closed_dates,created_by,reason,recorded_at,correlation_id,idempotency_key) values(:tenant,:code,:ordinal,:date,string_to_array(:days,',')::smallint[],string_to_array(:closed,',')::date[],:actor,:reason,:at,:correlation,:key)")
        .param("tenant", tenant)
        .param("code", p.code())
        .param("ordinal", r.version())
        .param("date", r.effectiveDate())
        .param("days", String.join(",", p.workingDays().stream().map(String::valueOf).toList()))
        .param(
            "closed", String.join(",", p.closedDates().stream().map(LocalDate::toString).toList()))
        .param("actor", e.createdBy())
        .param("reason", e.reason())
        .param("at", Timestamp.from(e.recordedAt()))
        .param("correlation", e.correlationId())
        .param("key", key)
        .update();
  }
}
