package com.atlas.modules.timekeeping.adapter.out.persistence;

import com.atlas.modules.timekeeping.application.model.TimeModels.*;
import com.atlas.modules.timekeeping.application.model.TimeModels.Period;
import com.atlas.modules.timekeeping.application.port.out.TimeStore;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

public final class JdbcTimeStore implements TimeStore {
  private final JdbcClient jdbc;
  private final ObjectMapper json = new ObjectMapper();

  public JdbcTimeStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private String encode(Object value) {
    return json.writeValueAsString(value);
  }

  private <T> T decode(String value, Class<T> type) {
    return json.readValue(value, type);
  }

  public Optional<String> batchFingerprint(String t, String s, String key) {
    return jdbc.sql(
            "select fingerprint from time_batch_command where tenant=:t and subject=:s and command_key=:k")
        .param("t", t)
        .param("s", s)
        .param("k", key)
        .query(String.class)
        .optional();
  }

  public void rememberBatch(String t, String s, String key, String fingerprint) {
    jdbc.sql(
            "insert into time_batch_command(tenant,subject,command_key,fingerprint) values(:t,:s,:k,:f)")
        .param("t", t)
        .param("s", s)
        .param("k", key)
        .param("f", fingerprint)
        .update();
  }

  public Optional<Period> period(String tenant, LocalDate week) {
    return jdbc.sql(
            "select snapshot::text from time_period where tenant=:t and week=:w order by version desc limit 1")
        .param("t", tenant)
        .param("w", week)
        .query((r, n) -> decode(r.getString(1), Period.class))
        .optional();
  }

  public List<Period> periodHistory(String tenant, LocalDate week) {
    return jdbc.sql(
            "select snapshot::text from time_period where tenant=:t and week=:w order by version desc")
        .param("t", tenant)
        .param("w", week)
        .query((r, n) -> decode(r.getString(1), Period.class))
        .list();
  }

  public Optional<Period> periodReceipt(String tenant, String subject, String key) {
    return jdbc.sql(
            "select snapshot::text from time_period where tenant=:t and actor=:a and command_key=:k")
        .param("t", tenant)
        .param("a", subject)
        .param("k", key)
        .query((r, n) -> decode(r.getString(1), Period.class))
        .optional();
  }

  public void savePeriod(String tenant, Period p) {
    jdbc.sql(
            "insert into time_period(tenant,week,version,actor,command_key,snapshot) values(:t,:w,:v,:a,:k,cast(:s as jsonb))")
        .param("t", tenant)
        .param("w", p.week())
        .param("v", p.version())
        .param("a", p.actor())
        .param("k", p.commandKey())
        .param("s", encode(p))
        .update();
  }

  public java.util.List<ActorAction> actors(String tenant, String entityId) {
    return jdbc.sql(
            "select distinct actor,action from time_audit where tenant=:t and entity_id=:id")
        .param("t", tenant)
        .param("id", entityId)
        .query((r, n) -> new ActorAction(r.getString(1), r.getString(2)))
        .list();
  }

  public void lock(String tenant, String employeeId) {
    jdbc.sql(
            "select pg_advisory_xact_lock(hashtextextended(current_schema()||'/timekeeping/'||:t||'/'||:e,0))")
        .param("t", tenant)
        .param("e", employeeId)
        .query()
        .singleRow();
  }

  public Optional<Receipt> receipt(String t, String s, String k) {
    return jdbc.sql(
            "select fingerprint,result::text from time_command where tenant=:t and subject=:s and command_key=:k")
        .param("t", t)
        .param("s", s)
        .param("k", k)
        .query((rs, n) -> new Receipt(rs.getString(1), decode(rs.getString(2), Outcome.class)))
        .optional();
  }

  public void remember(String t, String s, String k, String f, Outcome o) {
    jdbc.sql(
            "insert into time_command(tenant,subject,command_key,fingerprint,result) values(:t,:s,:k,:f,cast(:r as jsonb))")
        .param("t", t)
        .param("s", s)
        .param("k", k)
        .param("f", f)
        .param("r", encode(o))
        .update();
  }

  private Shift shift(ResultSet r, int n) throws SQLException {
    var end = r.getTimestamp("ended_at");
    return new Shift(
        r.getString("id"),
        r.getString("employee_id"),
        r.getObject("episode_start", LocalDate.class),
        r.getTimestamp("started_at").toInstant(),
        end == null ? null : end.toInstant(),
        r.getString("zone"),
        r.getLong("version"),
        r.getString("reason"),
        r.getTimestamp("recorded_at").toInstant());
  }

  public Optional<Shift> shift(String t, String id) {
    return jdbc.sql("select * from time_shift where tenant=:t and id=:id")
        .param("t", t)
        .param("id", id)
        .query(this::shift)
        .optional();
  }

  public Optional<Shift> openShift(String t, String e, LocalDate episode) {
    return jdbc.sql(
            "select * from time_shift where tenant=:t and employee_id=:e and episode_start=:episode and ended_at is null")
        .param("t", t)
        .param("e", e)
        .param("episode", episode)
        .query(this::shift)
        .optional();
  }

  public boolean overlaps(
      String t, String e, LocalDate episode, String except, Instant start, Instant end) {
    return jdbc.sql(
            "select exists(select 1 from time_shift where tenant=:t and employee_id=:e and episode_start=:episode and id<>:id and started_at<coalesce(cast(:end as timestamptz),'infinity'::timestamptz) and coalesce(ended_at,'infinity'::timestamptz)>cast(:start as timestamptz))")
        .param("t", t)
        .param("e", e)
        .param("episode", episode)
        .param("id", except)
        .param("start", Timestamp.from(start))
        .param("end", end == null ? null : Timestamp.from(end))
        .query(Boolean.class)
        .single();
  }

  public void saveShift(String t, Shift s) {
    var params = new java.util.HashMap<String, Object>();
    params.put("t", t);
    params.put("id", s.id());
    params.put("e", s.employeeId());
    params.put("ep", s.episodeStart());
    params.put("start", Timestamp.from(s.start()));
    params.put("end", s.end() == null ? null : Timestamp.from(s.end()));
    params.put("zone", s.zone());
    params.put("v", s.version());
    params.put("reason", s.reason());
    params.put("at", Timestamp.from(s.recordedAt()));
    int updated =
        jdbc.sql(
                "update time_shift set started_at=:start,ended_at=:end,zone=:zone,version=:v,reason=:reason,recorded_at=:at where tenant=:t and id=:id")
            .params(params)
            .update();
    if (updated == 0)
      jdbc.sql(
              "insert into time_shift(tenant,id,employee_id,episode_start,started_at,ended_at,zone,version,reason,recorded_at) values(:t,:id,:e,:ep,:start,:end,:zone,:v,:reason,:at)")
          .params(params)
          .update();
  }

  public Page<Shift> shifts(String t, String e, int page, int size) {
    long count =
        jdbc.sql("select count(*) from time_shift where tenant=:t and employee_id=:e")
            .param("t", t)
            .param("e", e)
            .query(Long.class)
            .single();
    var rows =
        jdbc.sql(
                "select * from time_shift where tenant=:t and employee_id=:e order by started_at desc,id limit :size offset :offset")
            .param("t", t)
            .param("e", e)
            .param("size", size)
            .param("offset", page * size)
            .query(this::shift)
            .list();
    return new Page<>(rows, count, page, size);
  }

  private Sheet sheet(ResultSet r, int n) throws SQLException {
    return decode(r.getString("snapshot"), Sheet.class);
  }

  public Optional<Sheet> sheet(String t, String id) {
    return jdbc.sql("select snapshot::text from time_sheet where tenant=:t and id=:id")
        .param("t", t)
        .param("id", id)
        .query(this::sheet)
        .optional();
  }

  public Optional<Sheet> week(String t, String e, LocalDate week, LocalDate episode) {
    return jdbc.sql(
            "select snapshot::text from time_sheet where tenant=:t and employee_id=:e and week=:w and episode_start=:ep")
        .param("t", t)
        .param("e", e)
        .param("w", week)
        .param("ep", episode)
        .query(this::sheet)
        .optional();
  }

  public void saveSheet(String t, Sheet s) {
    var params =
        Map.<String, Object>of(
            "t",
            t,
            "id",
            s.id(),
            "e",
            s.employeeId(),
            "ep",
            s.episodeStart(),
            "w",
            s.week(),
            "status",
            s.status(),
            "v",
            s.version(),
            "snapshot",
            encode(s));
    int updated =
        jdbc.sql(
                "update time_sheet set status=:status,version=:v,snapshot=cast(:snapshot as jsonb) where tenant=:t and id=:id")
            .params(params)
            .update();
    if (updated == 0)
      jdbc.sql(
              "insert into time_sheet(tenant,id,employee_id,episode_start,week,status,version,snapshot) values(:t,:id,:e,:ep,:w,:status,:v,cast(:snapshot as jsonb))")
          .params(params)
          .update();
  }

  public Page<Sheet> sheets(
      String t,
      List<String> employees,
      boolean all,
      String exclude,
      String status,
      int page,
      int size) {
    var ids = employees.isEmpty() ? List.of("__none__") : employees;
    var filter =
        " from time_sheet where tenant=:t and (:all or employee_id in(:ids)) and employee_id<>:exclude and (:status='ALL' or status=:status)";
    long total =
        jdbc.sql("select count(*)" + filter)
            .param("t", t)
            .param("all", all)
            .param("ids", ids)
            .param("exclude", exclude)
            .param("status", status)
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                "select snapshot::text"
                    + filter
                    + " order by week desc,id limit :size offset :offset")
            .param("t", t)
            .param("all", all)
            .param("ids", ids)
            .param("exclude", exclude)
            .param("status", status)
            .param("size", size)
            .param("offset", page * size)
            .query(this::sheet)
            .list();
    return new Page<>(items, total, page, size);
  }

  public List<ReportRow> report(
      String tenant, List<String> employeeIds, boolean all, ReportQuery q) {
    return jdbc.sql(
            """
        select s.id,s.employee_id,s.week,s.status,s.version,
          (s.snapshot->>'totalMinutes')::integer as total_minutes,
          coalesce((select sum(c.minutes) from time_costing_entry c
            where c.tenant=s.tenant and c.sheet_id=s.id),0)::integer as allocated_minutes
        from time_sheet s where s.tenant=:tenant and (:all or s.employee_id in (:ids))
          and s.week between :from and :through
          and (:employee='' or s.employee_id=:employee) and (:status='ALL' or s.status=:status)
        order by s.week desc,s.employee_id,s.id limit 1001
        """)
        .param("tenant", tenant)
        .param("all", all)
        .param("ids", employeeIds.isEmpty() ? List.of("__none__") : employeeIds)
        .param("from", q.from())
        .param("through", q.through())
        .param("employee", q.employeeId())
        .param("status", q.status())
        .query(
            (r, n) ->
                new ReportRow(
                    r.getString("id"),
                    r.getString("employee_id"),
                    r.getObject("week", LocalDate.class),
                    r.getString("status"),
                    r.getLong("version"),
                    r.getInt("total_minutes"),
                    r.getInt("allocated_minutes")))
        .list();
  }

  public void allocate(String t, Sheet s, Instant now) {
    for (var e : s.entries())
      if (e.minutes() > 0)
        jdbc.sql(
                "insert into time_costing_entry(tenant,sheet_id,work_date,minutes,activity_code,posted_at) values(:t,:id,:d,:m,:c,:at)")
            .param("t", t)
            .param("id", s.id())
            .param("d", e.date())
            .param("m", e.minutes())
            .param("c", e.code())
            .param("at", Timestamp.from(now))
            .update();
  }

  public List<Allocation> allocations(String t, String id) {
    return jdbc.sql(
            "select * from time_costing_entry where tenant=:t and sheet_id=:id order by work_date")
        .param("t", t)
        .param("id", id)
        .query(
            (r, n) ->
                new Allocation(
                    r.getString("sheet_id"),
                    r.getObject("work_date", LocalDate.class),
                    r.getInt("minutes"),
                    r.getString("activity_code"),
                    r.getTimestamp("posted_at").toInstant()))
        .list();
  }

  public void audit(
      String t,
      String employee,
      String id,
      String action,
      String actor,
      String reason,
      Object before,
      Object after,
      Instant now,
      String correlation) {
    jdbc.sql(
            "insert into time_audit(tenant,employee_id,entity_id,action,actor,reason,before_snapshot,after_snapshot,recorded_at,correlation_id) values(:t,:e,:id,:action,:actor,:reason,cast(:before as jsonb),cast(:after as jsonb),:at,:correlation)")
        .param("t", t)
        .param("e", employee)
        .param("id", id)
        .param("action", action)
        .param("actor", actor)
        .param("reason", reason)
        .param("before", encode(before))
        .param("after", encode(after))
        .param("at", Timestamp.from(now))
        .param("correlation", correlation)
        .update();
  }

  public Page<Audit> history(String t, String e, int page, int size) {
    var total =
        jdbc.sql("select count(*) from time_audit where tenant=:t and employee_id=:e")
            .param("t", t)
            .param("e", e)
            .query(Long.class)
            .single();
    var rows =
        jdbc.sql(
                "select * from time_audit where tenant=:t and employee_id=:e order by id desc limit :size offset :offset")
            .param("t", t)
            .param("e", e)
            .param("size", size)
            .param("offset", page * size)
            .query(
                (r, n) ->
                    new Audit(
                        r.getString("id"),
                        r.getString("entity_id"),
                        r.getString("action"),
                        r.getString("actor"),
                        r.getString("reason"),
                        r.getString("before_snapshot"),
                        r.getString("after_snapshot"),
                        r.getTimestamp("recorded_at").toInstant(),
                        r.getString("correlation_id")))
            .list();
    return new Page<>(rows, total, page, size);
  }
}
