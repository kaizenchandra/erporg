package com.atlas.modules.talent.adapter.out.persistence;

import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.application.port.out.TalentPorts.Store;
import com.atlas.shared.domain.CommandConflict;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Owned snapshot persistence; workflow and access policies stay in the core. */
public final class JdbcTalentStore implements Store {
  private final JdbcClient db;
  private final ObjectMapper json = new ObjectMapper();
  private static final Map<String, String> TABLES =
      Map.of(
          "requisitions",
          "tal_requisition",
          "onboarding",
          "tal_onboarding",
          "reviews",
          "tal_review",
          "courses",
          "tal_course",
          "learning",
          "tal_enrollment",
          "compensation",
          "tal_compensation",
          "succession",
          "tal_succession",
          "scenarios",
          "tal_scenario");

  public JdbcTalentStore(JdbcClient db) {
    this.db = db;
  }

  private String table(String kind) {
    var t = TABLES.get(kind);
    if (t == null) throw new IllegalArgumentException("Unknown talent capability.");
    return t;
  }

  public boolean enrolled(String t, String c, String e) {
    return db.sql(
            "select exists(select 1 from tal_enrollment where tenant=:t and snapshot->>'courseId'=:c and snapshot->>'employeeId'=:e)")
        .param("t", t)
        .param("c", c)
        .param("e", e)
        .query(Boolean.class)
        .single();
  }

  public String encode(Object v) {
    return json.writeValueAsString(v);
  }

  public <T> T decode(String s, Class<T> type) {
    return json.readValue(s, type);
  }

  public void lock(String t) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(current_schema()||'/talent/'||:t,0))")
        .param("t", t)
        .query((r, n) -> 0)
        .single();
  }

  public <T> Optional<T> find(String t, String k, String id, Class<T> type) {
    return db.sql("select snapshot::text from " + table(k) + " where tenant=:t and id=:id")
        .param("t", t)
        .param("id", id)
        .query((r, n) -> decode(r.getString(1), type))
        .optional();
  }

  public <T> Page<T> page(String t, String k, String employee, int page, Class<T> type) {
    var filter = " where tenant=:t and (:employee='' or snapshot->>'employeeId'=:employee)";
    var total =
        db.sql("select count(*) from " + table(k) + filter)
            .param("t", t)
            .param("employee", employee)
            .query(Long.class)
            .single();
    var items =
        db.sql(
                "select snapshot::text from "
                    + table(k)
                    + filter
                    + " order by (snapshot->'meta'->>'recordedAt')::timestamptz desc,id limit 10 offset :offset")
            .param("t", t)
            .param("employee", employee)
            .param("offset", page * 10)
            .query((r, n) -> decode(r.getString(1), type))
            .list();
    return new Page<>(items, total, page, 10);
  }

  public void insert(String t, String k, String id, Object s) {
    db.sql("insert into " + table(k) + "(tenant,id,snapshot) values(:t,:id,cast(:s as jsonb))")
        .param("t", t)
        .param("id", id)
        .param("s", encode(s))
        .update();
  }

  public void update(String t, String k, String id, long v, Object s) {
    if (db.sql(
                "update "
                    + table(k)
                    + " set snapshot=cast(:s as jsonb) where tenant=:t and id=:id and (snapshot->'meta'->>'version')::bigint=:v")
            .param("s", encode(s))
            .param("t", t)
            .param("id", id)
            .param("v", v)
            .update()
        != 1)
      throw new CommandConflict("Another user changed this record. Reload before trying again.");
  }

  public Optional<Receipt> receipt(String t, String s, String k) {
    return db.sql(
            "select fingerprint,result::text from tal_command where tenant=:t and subject=:s and command_key=:k")
        .param("t", t)
        .param("s", s)
        .param("k", k)
        .query((r, n) -> new Receipt(r.getString(1), r.getString(2)))
        .optional();
  }

  public void receipt(String t, String s, String k, String fp, Object r) {
    db.sql(
            "insert into tal_command(tenant,subject,command_key,fingerprint,result) values(:t,:s,:k,:fp,cast(:r as jsonb))")
        .param("t", t)
        .param("s", s)
        .param("k", k)
        .param("fp", fp)
        .param("r", encode(r))
        .update();
  }

  public void audit(String t, Audit a) {
    db.sql(
            "insert into tal_audit(tenant,id,kind,record_id,snapshot) values(:t,:id,:k,:r,cast(:s as jsonb))")
        .param("t", t)
        .param("id", a.id())
        .param("k", a.kind())
        .param("r", a.recordId())
        .param("s", encode(a))
        .update();
  }

  public List<Audit> history(String t, String k, String id, int p) {
    return db.sql(
            "select snapshot::text from tal_audit where tenant=:t and kind=:k and record_id=:id order by ((snapshot->>'after')::jsonb->'meta'->>'version')::bigint,id limit 20 offset :p")
        .param("t", t)
        .param("k", k)
        .param("id", id)
        .param("p", p * 20)
        .query((r, n) -> decode(r.getString(1), Audit.class))
        .list();
  }
}
