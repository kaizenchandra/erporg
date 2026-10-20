package com.atlas.modules.finance.adapter.out.persistence;

import com.atlas.modules.finance.application.model.FinanceModels.*;
import com.atlas.modules.finance.application.model.FinanceModels.Period;
import com.atlas.modules.finance.application.port.out.FinancePorts.Store;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

public final class JdbcFinanceStore implements Store {
  private final JdbcClient db;
  private final ObjectMapper json = new ObjectMapper();

  public JdbcFinanceStore(JdbcClient db) {
    this.db = db;
  }

  public <T> T decode(String s, Class<T> type) {
    return json.readValue(s, type);
  }

  public List<Grant> grants(Identity a) {
    return db.sql(
            "select entity,permission from fin_grant where tenant=:t and subject=:s and employee_id=:e order by entity,permission")
        .param("t", a.tenant())
        .param("s", a.subject())
        .param("e", a.employeeId())
        .query((r, n) -> new Grant(r.getString(1), r.getString(2)))
        .list();
  }

  public void lock(String t, String e) {
    db.sql(
            "select pg_advisory_xact_lock(hashtextextended(current_schema()||'/finance/'||:t||'/'||:e,0))")
        .param("t", t)
        .param("e", e)
        .query((r, n) -> 0)
        .single();
  }

  public Optional<Ledger> ledger(String t, String e) {
    return db.sql("select snapshot::text from fin_ledger where tenant=:t and entity=:e")
        .param("t", t)
        .param("e", e)
        .query((r, n) -> decode(r.getString(1), Ledger.class))
        .optional();
  }

  public void insertLedger(String t, Ledger l) {
    db.sql("insert into fin_ledger(tenant,entity,snapshot) values(:t,:e,cast(:s as jsonb))")
        .param("t", t)
        .param("e", l.entity())
        .param("s", json.writeValueAsString(l))
        .update();
  }

  public Optional<Journal> journal(String t, String id) {
    return db.sql("select snapshot::text from fin_journal where tenant=:t and id=:id")
        .param("t", t)
        .param("id", id)
        .query((r, n) -> decode(r.getString(1), Journal.class))
        .optional();
  }

  public Optional<Journal> source(String t, String e, String type, String id) {
    return db.sql(
            "select snapshot::text from fin_journal where tenant=:t and entity=:e and source_type=:type and source_id=:id")
        .param("t", t)
        .param("e", e)
        .param("type", type)
        .param("id", id)
        .query((r, n) -> decode(r.getString(1), Journal.class))
        .optional();
  }

  public void save(String t, Journal j) {
    var params =
        Map.of(
            "t",
            t,
            "id",
            j.id(),
            "e",
            j.entity(),
            "d",
            j.postingDate(),
            "type",
            j.sourceType(),
            "source",
            j.sourceId(),
            "status",
            j.status(),
            "v",
            j.version(),
            "s",
            json.writeValueAsString(j));
    int updated =
        db.sql(
                "update fin_journal set status=:status,version=:v,snapshot=cast(:s as jsonb),posting_date=:d where tenant=:t and id=:id")
            .params(params)
            .update();
    if (updated == 0)
      db.sql(
              "insert into fin_journal(tenant,id,entity,posting_date,source_type,source_id,status,version,snapshot) values(:t,:id,:e,:d,:type,:source,:status,:v,cast(:s as jsonb))")
          .params(params)
          .update();
  }

  public Page<Journal> journals(String t, String e, String status, int page) {
    var p = Map.of("t", t, "e", e, "status", status, "offset", page * 10);
    var filter =
        " from fin_journal where tenant=:t and entity=:e and (:status='ALL' or status=:status)";
    var count = db.sql("select count(*)" + filter).params(p).query(Long.class).single();
    var rows =
        db.sql(
                "select snapshot::text"
                    + filter
                    + " order by posting_date desc,id limit 10 offset :offset")
            .params(p)
            .query((r, n) -> decode(r.getString(1), Journal.class))
            .list();
    return new Page<>(rows, count, page, 10);
  }

  public Optional<Period> period(String t, String e, LocalDate month) {
    return db.sql(
            "select snapshot::text from fin_period where tenant=:t and entity=:e and month=:m order by version desc limit 1")
        .param("t", t)
        .param("e", e)
        .param("m", month)
        .query((r, n) -> decode(r.getString(1), Period.class))
        .optional();
  }

  public void insertPeriod(String t, String e, Period p) {
    db.sql(
            "insert into fin_period(tenant,entity,month,version,snapshot) values(:t,:e,:m,:v,cast(:s as jsonb))")
        .param("t", t)
        .param("e", e)
        .param("m", p.month())
        .param("v", p.version())
        .param("s", json.writeValueAsString(p))
        .update();
  }

  public Optional<Receipt> receipt(String t, String s, String k) {
    return db.sql(
            "select fingerprint,result::text from fin_command where tenant=:t and subject=:s and command_key=:k")
        .param("t", t)
        .param("s", s)
        .param("k", k)
        .query((r, n) -> new Receipt(r.getString(1), r.getString(2)))
        .optional();
  }

  public void receipt(String t, String s, String k, String fp, Object result) {
    db.sql("insert into fin_command values(:t,:s,:k,:fp,cast(:r as jsonb))")
        .param("t", t)
        .param("s", s)
        .param("k", k)
        .param("fp", fp)
        .param("r", json.writeValueAsString(result))
        .update();
  }

  public void audit(
      Identity a,
      String entity,
      String id,
      String action,
      String reason,
      Object before,
      Object after,
      Instant now,
      String correlation) {
    db.sql(
            "insert into fin_audit(tenant,entity,document_id,action,actor,reason,before_snapshot,after_snapshot,recorded_at,correlation_id) values(:t,:e,:id,:action,:actor,:reason,cast(:before as jsonb),cast(:after as jsonb),:at,:correlation)")
        .param("t", a.tenant())
        .param("e", entity)
        .param("id", id)
        .param("action", action)
        .param("actor", a.subject())
        .param("reason", reason)
        .param("before", json.writeValueAsString(before))
        .param("after", json.writeValueAsString(after))
        .param("at", java.sql.Timestamp.from(now))
        .param("correlation", correlation)
        .update();
  }

  public Page<Audit> history(String t, String e, int page) {
    var total =
        db.sql("select count(*) from fin_audit where tenant=:t and entity=:e")
            .param("t", t)
            .param("e", e)
            .query(Long.class)
            .single();
    var rows =
        db.sql(
                "select * from fin_audit where tenant=:t and entity=:e order by id desc limit 10 offset :o")
            .param("t", t)
            .param("e", e)
            .param("o", page * 10)
            .query(
                (r, n) ->
                    new Audit(
                        r.getString("id"),
                        r.getString("entity"),
                        r.getString("document_id"),
                        r.getString("action"),
                        r.getString("actor"),
                        r.getString("reason"),
                        r.getString("before_snapshot"),
                        r.getString("after_snapshot"),
                        r.getTimestamp("recorded_at").toInstant(),
                        r.getString("correlation_id")))
            .list();
    return new Page<>(rows, total, page, 10);
  }

  public List<Balance> balances(String t, String e, LocalDate from, LocalDate through) {
    return db.sql(
            "select line->>'account',line->>'costCentre',sum((line->>'debit')::numeric),sum((line->>'credit')::numeric) from fin_journal j cross join lateral jsonb_array_elements(j.snapshot->'lines') line where j.tenant=:t and j.entity=:e and j.status in ('POSTED','REVERSED') and j.posting_date between :f and :u group by 1,2 order by 1,2")
        .param("t", t)
        .param("e", e)
        .param("f", from)
        .param("u", through)
        .query(
            (r, n) ->
                new Balance(r.getString(1), r.getString(2), r.getBigDecimal(3), r.getBigDecimal(4)))
        .list();
  }
}
