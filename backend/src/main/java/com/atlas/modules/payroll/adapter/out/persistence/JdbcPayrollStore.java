package com.atlas.modules.payroll.adapter.out.persistence;

import com.atlas.modules.payroll.application.model.PayrollModels.*;
import com.atlas.modules.payroll.application.port.out.PayrollPorts.Store;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

public final class JdbcPayrollStore implements Store {
  private final JdbcClient db;
  private final ObjectMapper json = new ObjectMapper();

  public JdbcPayrollStore(JdbcClient db) {
    this.db = db;
  }

  public Run decode(String s) {
    return json.readValue(s, Run.class);
  }

  public void lock(String t, String k) {
    db.sql(
            "select pg_advisory_xact_lock(hashtextextended(current_schema()||'/payroll/'||:t||'/'||:k,0))")
        .param("t", t)
        .param("k", k)
        .query((r, n) -> 0)
        .single();
  }

  public Optional<Run> run(String t, String id) {
    return db.sql("select snapshot::text from pay_run where tenant=:t and id=:id")
        .param("t", t)
        .param("id", id)
        .query((r, n) -> decode(r.getString(1)))
        .optional();
  }

  public Optional<Run> source(String t, String id) {
    return db.sql("select snapshot::text from pay_run where tenant=:t and source_id=:id")
        .param("t", t)
        .param("id", id)
        .query((r, n) -> decode(r.getString(1)))
        .optional();
  }

  public void save(String t, Run r) {
    var params =
        Map.of(
            "t",
            t,
            "id",
            r.id(),
            "e",
            r.entity(),
            "employee",
            r.employeeId(),
            "source",
            r.source().id(),
            "status",
            r.status(),
            "v",
            r.version(),
            "s",
            json.writeValueAsString(r));
    int updated =
        db.sql(
                "update pay_run set status=:status,version=:v,snapshot=cast(:s as jsonb) where tenant=:t and id=:id")
            .params(params)
            .update();
    if (updated == 0)
      db.sql(
              "insert into pay_run(tenant,id,entity,employee_id,source_id,status,version,snapshot) values(:t,:id,:e,:employee,:source,:status,:v,cast(:s as jsonb))")
          .params(params)
          .update();
  }

  public Page runs(String t, String entity, String employee, boolean all, int page) {
    var p = Map.of("t", t, "entity", entity, "employee", employee, "all", all, "offset", page * 10);
    var filter =
        " from pay_run where tenant=:t and (:entity='' or entity=:entity) and (:all or (employee_id=:employee and status='FINALIZED'))";
    var total = db.sql("select count(*)" + filter).params(p).query(Long.class).single();
    var items =
        db.sql("select snapshot::text" + filter + " order by id limit 10 offset :offset")
            .params(p)
            .query((r, n) -> decode(r.getString(1)))
            .list();
    return new Page(items, total, page, 10);
  }

  public Optional<Receipt> receipt(String t, String s, String k) {
    return db.sql(
            "select fingerprint,result::text from pay_command where tenant=:t and subject=:s and command_key=:k")
        .param("t", t)
        .param("s", s)
        .param("k", k)
        .query((r, n) -> new Receipt(r.getString(1), r.getString(2)))
        .optional();
  }

  public void receipt(Identity a, String k, String fp, Run r) {
    db.sql("insert into pay_command values(:t,:s,:k,:fp,cast(:r as jsonb))")
        .param("t", a.tenant())
        .param("s", a.subject())
        .param("k", k)
        .param("fp", fp)
        .param("r", json.writeValueAsString(r))
        .update();
  }

  public void audit(
      Identity a,
      String id,
      String action,
      String reason,
      Object before,
      Object after,
      Instant now,
      String correlation) {
    db.sql(
            "insert into pay_audit(tenant,run_id,actor,action,reason,before_snapshot,after_snapshot,recorded_at,correlation_id) values(:t,:id,:actor,:action,:reason,cast(:before as jsonb),cast(:after as jsonb),:at,:correlation)")
        .param("t", a.tenant())
        .param("id", id)
        .param("actor", a.subject())
        .param("action", action)
        .param("reason", reason)
        .param("before", json.writeValueAsString(before))
        .param("after", json.writeValueAsString(after))
        .param("at", java.sql.Timestamp.from(now))
        .param("correlation", correlation)
        .update();
  }

  public List<Audit> history(String t, String id) {
    return db.sql(
            "select * from pay_audit where tenant=:t and run_id=:id order by id desc limit 100")
        .param("t", t)
        .param("id", id)
        .query(
            (r, n) ->
                new Audit(
                    r.getString("id"),
                    r.getString("run_id"),
                    r.getString("actor"),
                    r.getString("action"),
                    r.getString("reason"),
                    r.getString("before_snapshot"),
                    r.getString("after_snapshot"),
                    r.getTimestamp("recorded_at").toInstant(),
                    r.getString("correlation_id")))
        .list();
  }
}
