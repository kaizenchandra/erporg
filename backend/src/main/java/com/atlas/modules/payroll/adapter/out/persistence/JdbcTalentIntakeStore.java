package com.atlas.modules.payroll.adapter.out.persistence;

import com.atlas.modules.payroll.api.TalentIntakeAccess.Intake;
import com.atlas.modules.payroll.application.port.out.TalentIntakePorts.Store;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

public final class JdbcTalentIntakeStore implements Store {
  private final JdbcClient db;
  private final ObjectMapper json = new ObjectMapper();

  public JdbcTalentIntakeStore(JdbcClient db) {
    this.db = db;
  }

  public void lock(String t, String source) {
    db.sql(
            "select pg_advisory_xact_lock(hashtextextended(current_schema()||'/payroll-talent/'||:t||'/'||:s,0))")
        .param("t", t)
        .param("s", source)
        .query((r, n) -> 0)
        .single();
  }

  public Optional<Intake> find(String t, String source) {
    return db.sql("select snapshot::text from pay_talent_intake where tenant=:t and source_id=:s")
        .param("t", t)
        .param("s", source)
        .query((r, n) -> json.readValue(r.getString(1), Intake.class))
        .optional();
  }

  public void insert(String t, Intake i, String reason, String corr) {
    db.sql(
            "insert into pay_talent_intake(tenant,id,source_id,snapshot,reason,correlation_id) values(:t,:id,:s,cast(:v as jsonb),:r,:c)")
        .param("t", t)
        .param("id", i.id())
        .param("s", i.sourceId())
        .param("v", json.writeValueAsString(i))
        .param("r", reason)
        .param("c", corr)
        .update();
  }

  public List<Intake> pending(String t, int page) {
    return db.sql(
            "select snapshot::text from pay_talent_intake where tenant=:t order by (snapshot->>'recordedAt')::timestamptz,id limit 20 offset :p")
        .param("t", t)
        .param("p", page * 20)
        .query((r, n) -> json.readValue(r.getString(1), Intake.class))
        .list();
  }
}
