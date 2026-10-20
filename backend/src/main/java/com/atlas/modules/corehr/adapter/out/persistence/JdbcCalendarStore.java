package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.CalendarModels.*;
import com.atlas.modules.corehr.application.port.out.CalendarStore;
import com.atlas.modules.corehr.domain.model.WorkCalendar;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcCalendarStore implements CalendarStore {
  private final JdbcClient db;

  public JdbcCalendarStore(JdbcClient db) {
    this.db = db;
  }

  public boolean assignmentRecorded(String tenant, String code) {
    return db.sql(
            "select exists(select 1 from hr_calendar_assignment where tenant=:t and calendar_code=:c)")
        .param("t", tenant)
        .param("c", code)
        .query(Boolean.class)
        .single();
  }

  public void lock(String tenant) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(:scope,0))")
        .param("scope", "corehr:calendar:" + tenant)
        .query((r, n) -> true)
        .single();
  }

  private Entry entry(ResultSet r) throws SQLException {
    var days =
        Arrays.stream((Object[]) r.getArray("working_days").getArray())
            .map(d -> ((Number) d).intValue())
            .toList();
    var closed =
        Arrays.stream((Object[]) r.getArray("closed_dates").getArray())
            .map(d -> ((java.sql.Date) d).toLocalDate())
            .toList();
    return new Entry(
        new WorkCalendar(
            r.getString("code"),
            r.getString("name"),
            r.getString("time_zone"),
            r.getObject("effective_from", LocalDate.class),
            r.getObject("effective_until", LocalDate.class),
            days,
            closed),
        r.getString("created_by"),
        r.getString("reason"),
        r.getTimestamp("recorded_at").toInstant(),
        r.getString("correlation_id"));
  }

  public Optional<Entry> find(String tenant, String code) {
    return db.sql("select * from hr_work_calendar where tenant=:tenant and code=:code")
        .param("tenant", tenant)
        .param("code", code)
        .query((r, n) -> entry(r))
        .optional();
  }

  public Optional<Entry> duplicate(String tenant, String actor, String key) {
    return db.sql(
            "select * from hr_work_calendar where tenant=:tenant and created_by=:actor and idempotency_key=:key")
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query((r, n) -> entry(r))
        .optional();
  }

  public long count(String tenant) {
    return db.sql("select count(*) from hr_work_calendar where tenant=:tenant")
        .param("tenant", tenant)
        .query(Long.class)
        .single();
  }

  public Page list(String tenant, Query q, LocalDate date) {
    String from =
        " from hr_work_calendar b left join hr_work_calendar_retirement r on r.tenant=b.tenant and r.code=b.code";
    String where =
        " where b.tenant=:tenant and (position(lower(:search) in lower(b.code))>0 or position(lower(:search) in lower(b.name))>0) and (:status='ALL' or(:status='CURRENT' and b.effective_from<=:date and b.effective_until>=:date and(r.effective_date is null or r.effective_date>:date)) or(:status='PLANNED' and b.effective_from>:date) or(:status='EXPIRED' and b.effective_until<:date and r.code is null) or(:status='RETIRED' and r.effective_date<=:date))";
    var count =
        db.sql("select count(*)" + from + where)
            .param("tenant", tenant)
            .param("search", q.search())
            .param("status", q.status())
            .param("date", date)
            .query(Long.class)
            .single();
    var items =
        db.sql(
                "select b.*"
                    + from
                    + where
                    + " order by b.code collate \"C\" limit :size offset :offset")
            .param("tenant", tenant)
            .param("search", q.search())
            .param("status", q.status())
            .param("date", date)
            .param("size", q.size())
            .param("offset", q.page() * q.size())
            .query((r, n) -> entry(r))
            .list();
    var retirementDates = new LinkedHashMap<String, LocalDate>();
    if (!items.isEmpty())
      db.sql(
              "select code,effective_date from hr_work_calendar_retirement where tenant=:tenant and code in(:codes)")
          .param("tenant", tenant)
          .param("codes", items.stream().map(e -> e.calendar().code()).toList())
          .query(
              (r, n) ->
                  Map.entry(r.getString("code"), r.getObject("effective_date", LocalDate.class)))
          .list()
          .forEach(e -> retirementDates.put(e.getKey(), e.getValue()));
    return new Page(items, count, q.page(), q.size(), Map.copyOf(retirementDates));
  }

  public void insert(String tenant, Entry e, String key) {
    var c = e.calendar();
    db.sql(
            "insert into hr_work_calendar(tenant,code,name,time_zone,effective_from,effective_until,working_days,closed_dates,created_by,reason,recorded_at,correlation_id,idempotency_key) values(:tenant,:code,:name,:zone,:from,:until,string_to_array(:days,',')::smallint[],string_to_array(:closed,',')::date[],:actor,:reason,:at,:correlation,:key)")
        .param("tenant", tenant)
        .param("code", c.code())
        .param("name", c.name())
        .param("zone", c.timeZone())
        .param("from", c.effectiveFrom())
        .param("until", c.effectiveUntil())
        .param("days", String.join(",", c.workingDays().stream().map(String::valueOf).toList()))
        .param(
            "closed", String.join(",", c.closedDates().stream().map(LocalDate::toString).toList()))
        .param("actor", e.createdBy())
        .param("reason", e.reason())
        .param("at", Timestamp.from(e.recordedAt()))
        .param("correlation", e.correlationId())
        .param("key", key)
        .update();
  }
}
