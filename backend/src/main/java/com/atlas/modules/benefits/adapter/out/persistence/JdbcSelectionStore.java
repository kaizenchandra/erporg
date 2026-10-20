package com.atlas.modules.benefits.adapter.out.persistence;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.out.SelectionStore;
import com.atlas.modules.benefits.domain.model.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcSelectionStore
    implements SelectionStore, com.atlas.modules.benefits.application.port.out.SelectionInboxStore {
  private final JdbcClient db;

  public JdbcSelectionStore(JdbcClient db) {
    this.db = db;
  }

  public void lockEmployee(String tenant, String employeeId) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
        .param("key", "benefits:" + tenant + ":" + employeeId)
        .query((rs, n) -> 0)
        .single();
  }

  public Optional<BenefitPlan> plan(String tenant, String code) {
    return db.sql(
            "select code,name,currency,monthly_contribution,minimum_service_days,effective_from,rule_version from benefit_plan where tenant=:t and code=:c")
        .param("t", tenant)
        .param("c", code)
        .query(BenefitPlan.class)
        .optional();
  }

  public Optional<SelectionView> duplicate(String tenant, String subject, String key) {
    return db.sql(
            "select id from benefit_selection where tenant=:t and requested_by=:s and idempotency_key=:k")
        .param("t", tenant)
        .param("s", subject)
        .param("k", key)
        .query(String.class)
        .optional()
        .map(i -> view(tenant, i));
  }

  public Optional<SelectionView> locked(String tenant, String id) {
    return db.sql("select id from benefit_selection where tenant=:t and id=:id for update")
        .param("t", tenant)
        .param("id", id)
        .query(String.class)
        .optional()
        .map(i -> view(tenant, i));
  }

  public boolean active(String tenant, String employeeId, String code) {
    return db.sql(
                "select count(*) from benefit_selection where tenant=:t and employee_id=:e and plan_code=:p and status in ('PENDING','APPROVED')")
            .param("t", tenant)
            .param("e", employeeId)
            .param("p", code)
            .query(Long.class)
            .single()
        > 0;
  }

  private SelectionView view(String tenant, String id) {
    var dependants =
        db.sql(
                "select name,relationship from benefit_dependant where tenant=:t and selection_id=:id order by ordinal")
            .param("t", tenant)
            .param("id", id)
            .query(Dependant.class)
            .list();
    var history =
        db.sql(
                "select action,actor,reason,recorded_at,correlation_id from benefit_selection_audit where tenant=:t and selection_id=:id order by id")
            .param("t", tenant)
            .param("id", id)
            .query(SelectionAudit.class)
            .list();
    return db.sql(
            "select s.*,h.status handoff_status from benefit_selection s left join benefit_deduction_handoff h on h.tenant=s.tenant and h.selection_id=s.id where s.tenant=:t and s.id=:id")
        .param("t", tenant)
        .param("id", id)
        .query(
            (r, n) ->
                new SelectionView(
                    r.getString("id"),
                    r.getString("employee_id"),
                    r.getString("plan_code"),
                    r.getString("plan_name"),
                    r.getString("currency"),
                    r.getBigDecimal("monthly_contribution").setScale(2).toPlainString(),
                    r.getString("rule_version"),
                    r.getObject("effective_date", LocalDate.class),
                    r.getString("requested_by"),
                    r.getString("reason"),
                    r.getString("idempotency_key"),
                    r.getString("status"),
                    r.getLong("version"),
                    r.getString("decided_by"),
                    r.getString("decision_key"),
                    r.getString("decision_reason"),
                    dependants,
                    history,
                    r.getString("handoff_status")))
        .single();
  }

  private void audit(
      String tenant,
      String id,
      String action,
      String actor,
      String reason,
      Instant now,
      String correlation) {
    db.sql(
            "insert into benefit_selection_audit(tenant,selection_id,action,actor,reason,recorded_at,correlation_id) values(:t,:id,:action,:actor,:reason,:now,:corr)")
        .param("t", tenant)
        .param("id", id)
        .param("action", action)
        .param("actor", actor)
        .param("reason", reason)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("corr", correlation)
        .update();
  }

  public SelectionView insert(
      BenefitContext a,
      SubmitSelection c,
      BenefitPlan p,
      LocalDate date,
      Instant now,
      String correlation) {
    String id = UUID.randomUUID().toString();
    db.sql(
            "insert into benefit_selection(tenant,id,employee_id,plan_code,plan_name,currency,monthly_contribution,rule_version,effective_date,requested_by,reason,idempotency_key,recorded_at) values(:t,:id,:e,:p,:name,:currency,:amount,:rule,:date,:actor,:reason,:key,:now)")
        .param("t", a.tenant())
        .param("id", id)
        .param("e", a.employeeId())
        .param("p", p.code())
        .param("name", p.name())
        .param("currency", p.currency())
        .param("amount", p.monthlyContribution())
        .param("rule", p.ruleVersion())
        .param("date", date)
        .param("actor", a.subject())
        .param("reason", c.reason())
        .param("key", c.idempotencyKey())
        .param("now", now.atOffset(ZoneOffset.UTC))
        .update();
    for (int i = 0; i < c.dependants().size(); i++) {
      var d = c.dependants().get(i);
      db.sql("insert into benefit_dependant values(:t,:id,:ordinal,:name,:relationship)")
          .param("t", a.tenant())
          .param("id", id)
          .param("ordinal", i)
          .param("name", d.name())
          .param("relationship", d.relationship())
          .update();
    }
    audit(a.tenant(), id, "SUBMITTED", a.subject(), c.reason(), now, correlation);
    return view(a.tenant(), id);
  }

  public SelectionView decision(
      String tenant, String id, String actor, DecideSelection c, Instant now, String correlation) {
    db.sql(
            "update benefit_selection set status=:outcome,version=version+1,decided_by=:actor,decision_key=:key,decision_reason=:reason where tenant=:t and id=:id")
        .param("outcome", c.outcome())
        .param("actor", actor)
        .param("key", c.idempotencyKey())
        .param("reason", c.reason())
        .param("t", tenant)
        .param("id", id)
        .update();
    audit(tenant, id, c.outcome(), actor, c.reason(), now, correlation);
    if (c.outcome().equals("APPROVED"))
      db.sql(
              "insert into benefit_deduction_handoff(tenant,selection_id,employee_id,currency,monthly_contribution,effective_date,rule_version,recorded_at) select tenant,id,employee_id,currency,monthly_contribution,effective_date,rule_version,:now from benefit_selection where tenant=:t and id=:id")
          .param("now", now.atOffset(ZoneOffset.UTC))
          .param("t", tenant)
          .param("id", id)
          .update();
    return view(tenant, id);
  }

  public SelectionPage list(String tenant, String employeeId, int page, int size) {
    String condition = employeeId == null ? "tenant=:t" : "tenant=:t and employee_id=:e";
    var count =
        db.sql("select count(*) from benefit_selection where " + condition).param("t", tenant);
    if (employeeId != null) count.param("e", employeeId);
    var rows =
        db.sql(
                "select id from benefit_selection where "
                    + condition
                    + " order by recorded_at desc,id desc limit :size offset :offset")
            .param("t", tenant)
            .param("size", size)
            .param("offset", (long) page * size);
    if (employeeId != null) rows.param("e", employeeId);
    return new SelectionPage(
        rows.query(String.class).list().stream().map(id -> view(tenant, id)).toList(),
        count.query(Long.class).single(),
        page,
        size);
  }

  public SelectionPage inbox(SelectionInboxScope scope, SelectionInboxQuery q) {
    String condition = "tenant=:tenant and (:status='ALL' or status=:status)";
    if (scope.ownEmployeeId() != null) condition += " and employee_id=:owner";
    if (scope.excludedEmployeeId() != null) condition += " and employee_id<>:excludedEmployee";
    if (scope.excludedSubject() != null) condition += " and requested_by<>:excludedSubject";
    long total =
        inboxParameters(
                db.sql("select count(*) from benefit_selection where " + condition), scope, q)
            .query(Long.class)
            .single();
    var ids =
        inboxParameters(
                db.sql(
                    "select id from benefit_selection where "
                        + condition
                        + " order by recorded_at desc,id desc limit :size offset :offset"),
                scope,
                q)
            .param("size", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(String.class)
            .list();
    return new SelectionPage(
        ids.stream().map(id -> view(scope.tenant(), id)).toList(), total, q.page(), q.size());
  }

  private JdbcClient.StatementSpec inboxParameters(
      JdbcClient.StatementSpec sql, SelectionInboxScope scope, SelectionInboxQuery q) {
    sql.param("tenant", scope.tenant()).param("status", q.status());
    if (scope.ownEmployeeId() != null) sql.param("owner", scope.ownEmployeeId());
    if (scope.excludedEmployeeId() != null)
      sql.param("excludedEmployee", scope.excludedEmployeeId());
    if (scope.excludedSubject() != null) sql.param("excludedSubject", scope.excludedSubject());
    return sql;
  }
}
