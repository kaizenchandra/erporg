package com.atlas.modules.payroll.adapter.out.persistence;

import com.atlas.modules.payroll.api.CompensationAccess.Salary;
import com.atlas.modules.payroll.application.model.CompensationModels.*;
import com.atlas.modules.payroll.application.port.out.CompensationPorts.Store;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

public final class JdbcCompensationStore implements Store {
  private final JdbcClient db;
  private final ObjectMapper json = new ObjectMapper();

  public JdbcCompensationStore(JdbcClient d) {
    db = d;
  }

  public void lock(String t, String e) {
    db.sql(
            "select pg_advisory_xact_lock(hashtextextended(current_schema()||'/pay-compensation/'||:t||'/'||:e,0))")
        .param("t", t)
        .param("e", e)
        .query((r, n) -> 0)
        .single();
  }

  public boolean frozenAfter(String t, String e, LocalDate episode, LocalDate date) {
    return db.sql(
            "select exists(select 1 from pay_comp_run r cross join lateral jsonb_array_elements(r.snapshot->'days') d where r.tenant=:t and r.employee_id=:e and d->'salary'->>'episodeStart'=:episode and (d->>'date')::date>=:d)")
        .param("t", t)
        .param("e", e)
        .param("episode", episode.toString())
        .param("d", date)
        .query(Boolean.class)
        .single();
  }

  public Optional<Salary> sourceSalary(String t, String e, String source) {
    return db.sql(
            "select snapshot::text from pay_comp_salary where tenant=:t and employee_id=:e and source_id=:s")
        .param("t", t)
        .param("e", e)
        .param("s", source)
        .query((r, n) -> json.readValue(r.getString(1), Salary.class))
        .optional();
  }

  public Optional<Setup> setup(String t, String id) {
    return db.sql(
            "select coalesce(d.snapshot,r.snapshot)::text from pay_comp_setup r left join pay_comp_setup_decision d on d.tenant=r.tenant and d.setup_id=r.id where r.tenant=:t and r.id=:id")
        .param("t", t)
        .param("id", id)
        .query((r, n) -> json.readValue(r.getString(1), Setup.class))
        .optional();
  }

  public Optional<Setup> replay(String t, String a, String k) {
    return db.sql(
            "select coalesce(d.snapshot,r.snapshot)::text from pay_comp_setup r left join pay_comp_setup_decision d on d.tenant=r.tenant and d.setup_id=r.id where r.tenant=:t and r.actor=:a and r.command_key=:k")
        .param("t", t)
        .param("a", a)
        .param("k", k)
        .query((r, n) -> json.readValue(r.getString(1), Setup.class))
        .optional();
  }

  public void setup(String t, Setup s, String corr) {
    db.sql(
            "insert into pay_comp_setup(tenant,id,employee_id,actor,command_key,snapshot,correlation_id) values(:t,:id,:e,:a,:k,cast(:v as jsonb),:c)")
        .param("t", t)
        .param("id", s.id())
        .param("e", s.input().employeeId())
        .param("a", s.preparedBy())
        .param("k", s.input().idempotencyKey())
        .param("v", json.writeValueAsString(s))
        .param("c", corr)
        .update();
  }

  public void decision(String t, Setup s, String key, String corr) {
    db.sql(
            "insert into pay_comp_setup_decision(tenant,setup_id,snapshot,command_key,correlation_id) values(:t,:id,cast(:v as jsonb),:k,:c)")
        .param("t", t)
        .param("id", s.id())
        .param("v", json.writeValueAsString(s))
        .param("k", key)
        .param("c", corr)
        .update();
  }

  public List<Setup> setups(String t, String e) {
    return db.sql(
            "select coalesce(d.snapshot,r.snapshot)::text from pay_comp_setup r left join pay_comp_setup_decision d on d.tenant=r.tenant and d.setup_id=r.id where r.tenant=:t and r.employee_id=:e order by (r.snapshot->>'recordedAt')::timestamptz desc,r.id desc limit 100")
        .param("t", t)
        .param("e", e)
        .query((r, n) -> json.readValue(r.getString(1), Setup.class))
        .list();
  }

  public List<Salary> salaries(String t, String e) {
    return db.sql(
            "select snapshot::text from pay_comp_salary where tenant=:t and employee_id=:e order by revision desc limit 200")
        .param("t", t)
        .param("e", e)
        .query((r, n) -> json.readValue(r.getString(1), Salary.class))
        .list();
  }

  public Optional<Salary> salary(String t, String e, LocalDate date) {
    return db.sql(
            "select snapshot::text from pay_comp_salary where tenant=:t and employee_id=:e and effective_date<=:d order by effective_date desc,revision desc limit 1")
        .param("t", t)
        .param("e", e)
        .param("d", date)
        .query((r, n) -> json.readValue(r.getString(1), Salary.class))
        .optional();
  }

  public void salary(
      String t, Salary s, String actor, String reason, String corr, BigDecimalBonus bonus) {
    db.sql(
            "insert into pay_comp_salary(tenant,id,employee_id,source_id,revision,effective_date,snapshot,bonus,actor,reason,recorded_at,correlation_id) values(:t,:id,:e,:s,:v,:d,cast(:j as jsonb),:b,:a,:r,current_timestamp,:c)")
        .param("t", t)
        .param("id", s.id())
        .param("e", s.employeeId())
        .param("s", s.sourceId())
        .param("v", s.revision())
        .param("d", s.effectiveDate())
        .param("j", json.writeValueAsString(s))
        .param("b", bonus.amount())
        .param("a", actor)
        .param("r", reason)
        .param("c", corr)
        .update();
  }

  public List<Bonus> bonuses(String t, String e, LocalDate episode, LocalDate through) {
    return db.sql(
            "select s.id,s.source_id,s.bonus,s.effective_date from pay_comp_salary s where s.tenant=:t and s.employee_id=:e and (s.snapshot->>'episodeStart')::date=:episode and s.effective_date<=:d and s.bonus>0 and not exists(select 1 from pay_comp_bonus_use u where u.tenant=s.tenant and u.salary_id=s.id) order by s.revision limit 100")
        .param("t", t)
        .param("e", e)
        .param("episode", episode)
        .param("d", through)
        .query(
            (r, n) ->
                new Bonus(
                    r.getString(1),
                    r.getString(2),
                    r.getBigDecimal(3),
                    r.getObject(4, LocalDate.class)))
        .list();
  }

  public Optional<Calculation> calculation(String t, String id) {
    return db.sql("select snapshot::text from pay_comp_run where tenant=:t and run_id=:id")
        .param("t", t)
        .param("id", id)
        .query((r, n) -> json.readValue(r.getString(1), Calculation.class))
        .optional();
  }

  public void capture(String t, Calculation c) {
    db.sql(
            "insert into pay_comp_run(tenant,run_id,employee_id,snapshot) values(:t,:id,:e,cast(:v as jsonb))")
        .param("t", t)
        .param("id", c.runId())
        .param("e", c.employeeId())
        .param("v", json.writeValueAsString(c))
        .update();
    for (var b : c.bonuses())
      db.sql("insert into pay_comp_bonus_use(tenant,salary_id,run_id) values(:t,:s,:r)")
          .param("t", t)
          .param("s", b.id())
          .param("r", c.runId())
          .update();
  }

  public boolean released(String t, String id, String e) {
    return db.sql(
            "select exists(select 1 from pay_run where tenant=:t and id=:id and employee_id=:e and status='FINALIZED')")
        .param("t", t)
        .param("id", id)
        .param("e", e)
        .query(Boolean.class)
        .single();
  }
}
