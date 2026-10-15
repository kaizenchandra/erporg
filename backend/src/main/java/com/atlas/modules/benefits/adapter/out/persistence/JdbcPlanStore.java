package com.atlas.modules.benefits.adapter.out.persistence;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.out.PlanStore;
import com.atlas.modules.benefits.domain.model.BenefitPlan;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcPlanStore implements PlanStore {
  private final JdbcClient db;

  public JdbcPlanStore(JdbcClient db) {
    this.db = db;
  }

  public List<BenefitPlan> plans(String tenant, int limit) {
    return db.sql(
            "select code,name,currency,monthly_contribution,minimum_service_days,effective_from,rule_version from benefit_plan where tenant=:tenant order by name,code limit :limit")
        .param("tenant", tenant)
        .param("limit", limit)
        .query(BenefitPlan.class)
        .list();
  }

  public Optional<CreatePlan> duplicate(String tenant, String subject, String key) {
    return db.sql(
            "select p.code,p.name,p.currency,p.monthly_contribution::text monthly_contribution,p.minimum_service_days::text minimum_service_days,p.effective_from,a.reason,p.idempotency_key from benefit_plan p join benefit_plan_audit a on a.tenant=p.tenant and a.code=p.code where p.tenant=:tenant and p.created_by=:actor and p.idempotency_key=:key")
        .param("tenant", tenant)
        .param("actor", subject)
        .param("key", key)
        .query(CreatePlan.class)
        .optional();
  }

  public void insert(
      String tenant,
      String actor,
      BenefitPlan p,
      String reason,
      String key,
      Instant now,
      String correlation) {
    db.sql(
            "insert into benefit_plan(tenant,code,name,currency,monthly_contribution,minimum_service_days,effective_from,created_by,idempotency_key,rule_version) values(:tenant,:code,:name,:currency,:amount,:days,:date,:actor,:key,:rule)")
        .param("tenant", tenant)
        .param("code", p.code())
        .param("name", p.name())
        .param("currency", p.currency())
        .param("amount", p.monthlyContribution())
        .param("days", p.minimumServiceDays())
        .param("date", p.effectiveFrom())
        .param("actor", actor)
        .param("key", key)
        .param("rule", p.ruleVersion())
        .update();
    db.sql(
            "insert into benefit_plan_audit(tenant,code,actor,reason,recorded_at,effective_from,correlation_id) values(:tenant,:code,:actor,:reason,:now,:date,:correlation)")
        .param("tenant", tenant)
        .param("code", p.code())
        .param("actor", actor)
        .param("reason", reason)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("date", p.effectiveFrom())
        .param("correlation", correlation)
        .update();
  }

  public List<PlanAudit> history(String tenant) {
    return db.sql(
            "select code,actor,reason,recorded_at,effective_from,correlation_id from benefit_plan_audit where tenant=:tenant order by recorded_at desc,id desc limit 100")
        .param("tenant", tenant)
        .query(PlanAudit.class)
        .list();
  }
}
